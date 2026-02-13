# KDoc & Documentation Standards

When documenting Kotlin code, follow these rules to ensure clarity and navigability.

## 1. The KDoc Block
Use `/** ... */` for classes, properties, and functions.

## 2. Cross-Linking with [ ]
This is critical for navigability.
- **Internal Links**: Link to parameters using `[parameterName]`.
- **External Links**: Link to other classes or methods using `[ClassName]` or `[ClassName.methodName]`.
- **Interacting Methods**: If a method calls `processData()`, the KDoc should mention: "Delegates the actual transformation to [processData]."

## 3. Tag Usage
- `@param`: Document every parameter.
- `@return`: Document the return value unless it is `Unit`.
- `@throws` or `@exception`: Document any checked or common runtime exceptions.
- `@see`: Link to related classes or external documentation URLs.

## 4. Writing Style
- **Summary Line**: The first sentence should be a concise summary of what the symbol does.
- **Detailed Description**: Follow the summary with a blank line and then more detail if the logic is complex.
- **Avoid "What", Explain "Why"**: Don't just restate the function name. Explain the intent and side effects.
