import ast
import sys
import json

class SecurityValidator(ast.NodeVisitor):
    def __init__(self, blocked_imports, blocked_functions):
        self.blocked_imports = set(blocked_imports)
        self.blocked_functions = set(blocked_functions)
        self.errors = []
        self.warnings = []
        self.variables = {}

    def visit_Assign(self, node):
        value = self._get_constant_value(node.value)
        for target in node.targets:
            if isinstance(target, ast.Name):
                self.variables[target.id] = value
        self.generic_visit(node)

    def visit_Import(self, node):
        for alias in node.names:
            if alias.name.split('.')[0] in self.blocked_imports:
                self.errors.append(f"Import '{alias.name}' is not allowed")
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        if node.module and node.module.split('.')[0] in self.blocked_imports:
            self.errors.append(f"Import from '{node.module}' is not allowed")
        self.generic_visit(node)

    def visit_Call(self, node):
        # Check for direct function calls
        func_name = self._get_func_name(node.func)
        if func_name in self.blocked_functions:
            self.errors.append(f"Function '{func_name}' is not allowed")

        # Check for getattr(obj, 'blocked_func')
        if isinstance(node.func, ast.Name) and node.func.id == 'getattr':
            if len(node.args) >= 2:
                attr_name = self._get_constant_value(node.args[1])
                if attr_name in [f.split('.')[-1] for f in self.blocked_functions]:
                    self.errors.append(f"Access to blocked function via getattr: '{attr_name}'")

        # Check for __import__('blocked_module')
        if isinstance(node.func, ast.Name) and node.func.id == '__import__':
            if len(node.args) >= 1:
                module_name = self._get_constant_value(node.args[0])
                if module_name and module_name.split('.')[0] in self.blocked_imports:
                    self.errors.append(f"Import via __import__ is not allowed: '{module_name}'")

        # Check for importlib.import_module('blocked_module')
        if func_name == 'importlib.import_module':
             if len(node.args) >= 1:
                module_name = self._get_constant_value(node.args[0])
                if module_name and module_name.split('.')[0] in self.blocked_imports:
                    self.errors.append(f"Import via import_module is not allowed: '{module_name}'")

        self.generic_visit(node)

    def _get_func_name(self, node):
        if isinstance(node, ast.Name):
            return node.id
        elif isinstance(node, ast.Attribute):
            value = self._get_func_name(node.value)
            if value:
                return f"{value}.{node.attr}"
        return None

    def _get_constant_value(self, node):
        if isinstance(node, ast.Constant):
            return node.value
        elif isinstance(node, ast.Name):
            return self.variables.get(node.id)
        elif isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            left = self._get_constant_value(node.left)
            right = self._get_constant_value(node.right)
            if isinstance(left, str) and isinstance(right, str):
                return left + right
        elif isinstance(node, ast.JoinedStr):
            parts = []
            for value in node.values:
                val = self._get_constant_value(value)
                if val is not None:
                    parts.append(str(val))
                else:
                    return None
            return "".join(parts)
        elif isinstance(node, ast.FormattedValue):
            return self._get_constant_value(node.value)
        return None

def validate_script(script, blocked_imports, blocked_functions):
    try:
        tree = ast.parse(script)
        validator = SecurityValidator(blocked_imports, blocked_functions)
        validator.visit(tree)
        return {
            "isValid": len(validator.errors) == 0,
            "errors": validator.errors,
            "warnings": validator.warnings
        }
    except SyntaxError as e:
        return {
            "isValid": False,
            "errors": [f"Syntax error: {str(e)}"],
            "warnings": []
        }
    except Exception as e:
        return {
            "isValid": False,
            "errors": [f"Validation error: {str(e)}"],
            "warnings": []
        }

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"isValid": False, "errors": ["No script provided"], "warnings": []}))
        sys.exit(1)

    try:
        input_data = json.load(sys.stdin)
        script = input_data.get("script", "")
        blocked_imports = input_data.get("blocked_imports", [])
        blocked_functions = input_data.get("blocked_functions", [])

        result = validate_script(script, blocked_imports, blocked_functions)
        print(json.dumps(result))
    except Exception as e:
        print(json.dumps({"isValid": False, "errors": [f"Validator process error: {str(e)}"], "warnings": []}))
