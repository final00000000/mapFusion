"""验证字段/枚举定位及完整字面量比对，不声称完整 Kotlin AST。"""
import unittest
from tools.kotlin_source import code_tokens, mask_source, member_targets


class KotlinSourceTest(unittest.TestCase):
    def names(self, source):
        return [(target.kind, target.name) for target in member_targets(mask_source(source)[0])]

    def test_primary_properties_with_generics_and_function_types(self):
        source = 'data class Token<T : Any>(val identity: T, var epoch: Long, val callback: (Int) -> Unit)'
        self.assertEqual([('property', 'identity'), ('property', 'epoch'), ('property', 'callback')], self.names(source))

    def test_primary_defaults_do_not_turn_local_variables_into_properties(self):
        source = 'class Host(val choice: Int = run { val local = 1; local }) { val state = 1 }'
        self.assertEqual([('property', 'choice'), ('property', 'state')], self.names(source))

    def test_enum_arguments_annotations_and_anonymous_entry_members(self):
        source = 'enum class Tone(val color: Int) { @Deprecated("x,y") A(rgb(1, 2, 3)) { val label = "a" }, B(2); fun f() { val local = 1 } }'
        self.assertEqual([('property', 'color'), ('enum-entry', 'A'), ('property', 'label'), ('enum-entry', 'B')], self.names(source))

    def test_initializers_accessors_and_function_locals_are_excluded(self):
        source = 'class Host { init { val initialized = 1 }; val value = run { val temporary = 2; temporary }; val count: Int get() { val result = 1; return result }; fun f() { val local = 1 } }'
        self.assertEqual([('property', 'value'), ('property', 'count')], self.names(source))

    def test_anonymous_object_members_inside_function_call_are_detected(self):
        source = 'fun f() = install(object : Listener { val identity = 1; override fun done() { val local = 2 } })'
        self.assertEqual([('property', 'identity')], self.names(source))

    def test_bodyless_types_do_not_capture_the_next_type_body(self):
        source = 'data class Choice(val mode: Int)\nsealed interface Action { data object Stop : Action; data class Start(val speed: Int) : Action }\nobject Constants { val interval = 2 }'
        self.assertEqual([('property', 'mode'), ('property', 'speed'), ('property', 'interval')], self.names(source))

    def test_nested_templates_and_raw_strings_are_masked_as_complete_literals(self):
        source = 'val text = "${value ?: "class fun val fake"}"\nval raw = """${listOf("enum", "property").joinToString(",")}"""\n'
        masked, docs = mask_source(source)
        self.assertNotIn('fake', masked)
        self.assertNotIn('enum', masked)
        self.assertEqual([], docs)
        self.assertEqual([('property', 'text'), ('property', 'raw')], self.names(source))
        self.assertIn('"${value ?: "class fun val fake"}"', code_tokens(source))

    def test_quoted_identifiers_and_class_literals_do_not_create_extra_types(self):
        source = 'val `field-name` = Host::class\nclass Actual { val value = 1 }'
        self.assertEqual([('property', 'field-name'), ('property', 'value')], self.names(source))

    def test_comment_only_edits_preserve_full_code_tokens(self):
        before = 'data class S(val title: String = "a b", val speed: Int = 60)'
        after = '/** 数据。 */ data class S(/** 标题。 */ val title: String = "a b",\n/** 速度 km/h。 */ val speed: Int = 60)'
        self.assertEqual(code_tokens(before), code_tokens(after))

    def test_literal_changes_and_enum_reordering_are_not_hidden(self):
        for before, after in [('val x = "${a ?: "a b"}"', 'val x = "${a ?: "a  b"}"'),
                              ('val x = 60', 'val x = 61'), ('enum class E { A, B }', 'enum class E { B, A }')]:
            self.assertNotEqual(code_tokens(before), code_tokens(after))

    def test_operator_token_boundaries_are_preserved(self):
        self.assertNotEqual(code_tokens('a + +b'), code_tokens('a++b'))
        self.assertEqual(code_tokens('a ?. b ?: c'), code_tokens('a?.b?:c'))


if __name__ == '__main__':
    unittest.main()
