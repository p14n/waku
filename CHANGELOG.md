# Changelog

## Code Review Fixes - 2026-02-28

### Critical Issues Fixed

#### 1. Removed Debug Code
- **Issue**: Debug `println` statements left in production code
- **Fixed**: 
  - Removed `println "Deferred realized HHHHHHHHHHHHHH"` from `run-workflow`
  - Removed `println workflow-name workflow-id workflow-step "PREVIOUS"` from `then!`
  - Replaced with proper logging using `clojure.tools.logging`

#### 2. Removed Commented-Out Code
- **Issue**: Large block of commented protocol extension (lines 9-21)
- **Fixed**: Removed dead code, kept only the active implementation with explanatory comment

#### 3. Improved Error Handling
- **Issue**: Error handlers in deferred callbacks just used `println`
- **Fixed**: 
  - Added proper error logging with context in `run-workflow`
  - Added proper error logging with context in `execute-step`
  - Errors now include workflow-name, workflow-id, and step information

### High Priority Improvements

#### 4. Added Comprehensive Docstrings
Added detailed docstrings to all public functions and the protocol:
- `StepStore` protocol and all its methods
- `set-store!`
- `register-callback-workflow!`
- `lookup-callback-workflow`
- `run-workflow`
- `run-callback-workflow`
- `run-workflow-from-callback`
- `then!`
- `then!*`
- `then!*>`
- `then-until!`
- Internal functions: `make-store-callback-token-fn`, `execute-step`

Each docstring includes:
- Purpose description
- Parameter explanations
- Return value description
- Usage examples where appropriate

#### 5. Added Namespace Documentation
- Added comprehensive namespace-level docstring explaining the library's purpose

#### 6. Documented Dynamic Vars
- Added docstrings to `*current-workflow*` and `create-callback-token!` explaining their purpose and usage
- Added docstrings to `store-atom` and `callback-atom` explaining they are global state

#### 7. Added Logging Framework
- **Added dependencies**: 
  - `org.clojure/tools.logging {:mvn/version "1.3.0"}`
  - `org.slf4j/slf4j-api {:mvn/version "2.0.7"}`
- **Usage**: Debug and error logging throughout the codebase
- **Benefits**: Proper observability without polluting stdout

### Code Quality Improvements

#### 8. Improved Code Comments
- Added explanatory comment for the `flow/Flow` protocol extension
- Added context comments in `run-workflow` explaining deferred re-execution behavior
- Added context comments in `then!` explaining cache hit behavior

#### 9. Better Error Messages
- Improved error message formatting for consistency
- Added more context to exception messages

### Testing

- ✅ All existing tests pass (2 tests, 31 assertions, 0 failures)
- No breaking changes to the API
- All functionality preserved

### Files Modified

1. `src/com/p14n/waku/core.clj` - Major improvements to documentation and error handling
2. `deps.edn` - Added logging dependencies

### Remaining Issues (Not Fixed)

The following issues were identified but not fixed in this round:

1. **Global Mutable State** - `store-atom` and `callback-atom` remain global
   - Recommendation: Consider dependency injection or component-based approach
   
2. **Recursive Workflow Re-execution** - The deferred re-execution logic in `run-workflow`
   - May need architectural review to ensure it doesn't cause issues
   
3. **Potential Race Conditions** - Check-then-act pattern in `then!`
   - Consider atomic operations in store implementations
   
4. **Test Coverage** - Missing tests for edge cases
   - No tests for concurrent execution
   - No tests for store failures
   - Limited error scenario coverage

5. **Code Complexity** - `then!` has high cyclomatic complexity (cc=10)
   - Consider refactoring into smaller functions

### Migration Notes

No breaking changes. This is a backward-compatible improvement release.

Users will now see proper log messages instead of println output. To see debug logs, configure your logging framework appropriately.

