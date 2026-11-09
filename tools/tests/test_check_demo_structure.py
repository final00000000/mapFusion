"""验证轻量 KDoc 检查器的边界，不把它当作完整 Kotlin 解析器。"""
import tempfile
import unittest
from pathlib import Path
from tools.check_demo_structure import check_source, production_files


class DemoStructureTest(unittest.TestCase):
    def test_named_declarations_and_primary_constructor(self):
        source = '/** 页面职责。 */\ninternal class Page private constructor() {}\n'
        count, errors = check_source(source)
        self.assertEqual(2, count)
        self.assertEqual([], errors)

    def test_extensions_overrides_local_methods_and_secondary_constructors(self):
        source = '''/** 状态宿主。 */
class Host {
    /** 创建值副本。 */
    constructor(value: Int)
    /** 释放当前会话。 */
    override fun close() {
        /** 只清理局部句柄。 */
        fun clear() {}
    }
}
/** 展示值。 */
private fun String.label() = this
'''
        count, errors = check_source(source)
        self.assertEqual(5, count)
        self.assertEqual([], errors)

    def test_anonymous_object_and_companion_have_own_documentation(self):
        source = '''/** 宿主。 */
class Host {
    /** 恢复键。 */
    companion object {}
    /** 页面持有的监听对象引用。 */
    val callback =
        /** 回调桥。 */
        object : Listener {
            /** 投递有效结果。 */
            override fun done() {}
        }
}
'''
        count, errors = check_source(source)
        self.assertEqual(4, count)
        self.assertEqual([], errors)

    def test_comments_strings_and_class_literals_are_not_declarations(self):
        source = '''// class False fun false()
/** 原始字符串示例。 */
val raw = """object fun interface"""
/** 普通字符串示例。 */
val text = "class object constructor fun"
/** 类型引用不是类声明。 */
val type = Host::class
/* outer /* class Inner */ fun ignored() */
/** 真正接口。 */
fun interface Listener {}
'''
        count, errors = check_source(source)
        self.assertEqual(1, count)
        self.assertEqual([], errors)

    def test_annotations_can_separate_documentation_and_declaration(self):
        source = '/** 保留原始结果。 */\n@Deprecated("old")\n@Suppress("DEPRECATION")\noverride fun result() {}\n'
        self.assertEqual((1, []), check_source(source))

    def test_missing_or_non_chinese_kdoc_is_rejected(self):
        for source in ('class Missing {}', '/** English only. */\nclass Missing {}', '// 中文行注释\nfun missing() {}'):
            count, errors = check_source(source)
            self.assertEqual(1, count)
            self.assertEqual(1, len(errors))
            self.assertIn('missing-chinese-kdoc', errors[0][1])

    def test_method_cannot_borrow_class_or_previous_method_documentation(self):
        source = '/** 类职责。 */\nclass Host {\nfun missing() {}\n/** 已说明方法。 */\nfun present() {}\nfun missingAgain() {}\n}'
        count, errors = check_source(source)
        self.assertEqual(4, count)
        self.assertEqual([3, 6], [line for line, _ in errors])

    def test_line_limit_is_independent_of_comments(self):
        count, errors = check_source('/** 中文类。 */\nclass Small {}\n\n', max_lines=2)
        self.assertEqual(1, count)
        self.assertEqual([(1, 'file-too-large:3>2')], errors)

    def test_discovery_excludes_sdk_tests_and_generated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = ['app/src/main/kotlin/Demo.kt', 'app/src/debug/kotlin/Debug.kt',
                     'app/src/test/kotlin/Test.kt', 'app/build/generated/Generated.kt',
                     'map-api/src/main/kotlin/Api.kt']
            for name in paths:
                file = root / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text('', encoding='utf-8')
            self.assertEqual(['app/src/debug/kotlin/Debug.kt', 'app/src/main/kotlin/Demo.kt'],
                             [file.relative_to(root).as_posix() for file in production_files(root)])

    def test_data_fields_need_individual_chinese_kdoc(self):
        source = '/** 状态类。 */\ndata class State(val ready: Boolean, val speed: Int)'
        count, errors = check_source(source)
        self.assertEqual(1, count)
        self.assertEqual(['missing-chinese-kdoc:property:ready', 'missing-chinese-kdoc:property:speed'],
                         [rule for _, rule in errors])

    def test_data_fields_and_enum_constructor_property_can_be_documented(self):
        source = '/** 分类。 */\nenum class Category(/** 中文标题。 */ val title: String) { /** 常用项。 */ COMMON("常用") }'
        self.assertEqual((1, []), check_source(source))

    def test_each_enum_entry_needs_its_own_chinese_kdoc(self):
        source = '/** 语义。 */\nenum class Tone { NEUTRAL, /** 成功语义。 */ SUCCESS, ERROR }'
        count, errors = check_source(source)
        self.assertEqual(1, count)
        self.assertEqual(['missing-chinese-kdoc:enum-entry:NEUTRAL', 'missing-chinese-kdoc:enum-entry:ERROR'],
                         [rule for _, rule in errors])

    def test_members_and_top_level_constants_cannot_borrow_previous_docs(self):
        source = '/** 宿主。 */\nclass Host {\nvar closed = false\n/** 默认常量。 */\ncompanion object { const val LIMIT = 2 }\n}\nval value = 1'
        _, errors = check_source(source)
        self.assertEqual(['missing-chinese-kdoc:property:closed', 'missing-chinese-kdoc:property:LIMIT',
                          'missing-chinese-kdoc:property:value'], [rule for _, rule in errors])

    def test_local_variables_do_not_require_field_kdoc(self):
        source = '/** 宿主。 */\nclass Host { /** 查询。 */ fun query() { val value = 1; listOf(1).forEach { val item = it } } }'
        self.assertEqual((2, []), check_source(source))

    def test_annotated_member_property_is_supported(self):
        source = '/** 宿主。 */\nclass Host { /** 跨线程关闭标记。 */ @Volatile private var closed = false }'
        self.assertEqual((1, []), check_source(source))


if __name__ == '__main__':
    unittest.main()
