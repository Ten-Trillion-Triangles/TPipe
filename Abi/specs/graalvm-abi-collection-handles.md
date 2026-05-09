# GraalVM Native ABI Specification — Collection Handles

**Spec File:** graalvm-abi-collection-handles.md  
**Version:** 0.2.0-draft  
**Created:** 2026-05-07  
**Status:** Draft

**Design Decision:** Typed Builder Pattern (Option A) — explicit create/push/get methods per element type.

---

## 1. TPipe_ListHandle Overview

`TPipe_ListHandle` is a typed list container. Each list has an **element type** determined at creation time. Element type cannot change after creation.

### 1.1 Element Types

```c
typedef enum {
    TPIPE_LIST_STRING,
    TPIPE_LIST_INT32,
    TPIPE_LIST_INT64,
    TPIPE_LIST_FLOAT32,
    TPIPE_LIST_FLOAT64,
    TPIPE_LIST_BOOL,
    TPIPE_LIST_HANDLE   // Generic handle (any TPipe_Handle subtype)
} TPipe_ListElementType;
```

---

## 2. List Lifecycle Functions

### 2.1 Creation

```c
// Create an empty list of the specified element type.
// Caller receives handle with refcount = 1.

TPipe_ListHandle TPipe_List_create(TPipe_ListElementType element_type);
```

**Returns:** New list handle, or `TPIPE_INVALID_HANDLE` on failure.

---

### 2.2 Destruction

```c
// Decrement refcount by 1. If count reaches 0, list is GC'd.
// This does NOT release any handles inside the list - caller must
// release those separately if needed.

TPipe_Result TPipe_List_release(TPipe_ListHandle list);
```

**Note:** If list contains handles (type = `TPIPE_LIST_HANDLE`), those handles must be explicitly released by the caller before releasing the list, unless transfer of ownership is intended.

---

## 3. String List Operations

### 3.1 Creation

```c
TPipe_ListHandle TPipe_List_createString(void);
// Equivalent to: TPipe_List_create(TPIPE_LIST_STRING)
```

### 3.2 Push Operations

```c
// Append a string to the end of the list.
// String is copied by TPipe (caller retains ownership of original).

TPipe_Result TPipe_List_pushString(TPipe_ListHandle list, const char* value);


// Push a substring (useful for JSON parsing without allocating).
// Copies up to max_len characters from value.

TPipe_Result TPipe_List_pushStringN(TPipe_ListHandle list, const char* value, int max_len);
```

### 3.3 Query Operations

```c
// Get number of elements in list.

int32_t TPipe_List_size(TPipe_ListHandle list);


// Get string at index. Returns pointer to internal storage (do not free).
// Returns NULL if index out of bounds or type mismatch.

const char* TPipe_List_getString(TPipe_ListHandle list, int32_t index);


// Check if list is empty.

int TPipe_List_isEmpty(TPipe_ListHandle list);
```

---

## 4. Int32 List Operations

### 4.1 Creation

```c
TPipe_ListHandle TPipe_List_createInt32(void);
// Equivalent to: TPipe_List_create(TPIPE_LIST_INT32)
```

### 4.2 Push Operations

```c
TPipe_Result TPipe_List_pushInt32(TPipe_ListHandle list, int32_t value);
```

### 4.3 Query Operations

```c
int32_t TPipe_List_size(TPipe_ListHandle list);  // shared

int32_t TPipe_List_getInt32(TPipe_ListHandle list, int32_t index);
```

---

## 5. Int64 List Operations

```c
TPipe_ListHandle TPipe_List_createInt64(void);

TPipe_Result TPipe_List_pushInt64(TPipe_ListHandle list, int64_t value);

int64_t TPipe_List_getInt64(TPipe_ListHandle list, int32_t index);
```

---

## 6. Float32 List Operations

```c
TPipe_ListHandle TPipe_List_createFloat32(void);

TPipe_Result TPipe_List_pushFloat32(TPipe_ListHandle list, float value);

float TPipe_List_getFloat32(TPipe_ListHandle list, int32_t index);
```

---

## 7. Float64 List Operations

```c
TPipe_ListHandle TPipe_List_createFloat64(void);

TPipe_Result TPipe_List_pushFloat64(TPipe_ListHandle list, double value);

double TPipe_List_getFloat64(TPipe_ListHandle list, int32_t index);
```

---

## 8. Bool List Operations

```c
TPipe_ListHandle TPipe_List_createBool(void);

TPipe_Result TPipe_List_pushBool(TPipe_ListHandle list, int value);  // 0=false, non-zero=true

int TPipe_List_getBool(TPipe_ListHandle list, int32_t index);  // returns 0 or 1
```

---

## 9. Handle List Operations

### 9.1 Creation

```c
TPipe_ListHandle TPipe_List_createHandle(void);
// Creates list that can hold any TPipe_Handle subtype
```

### 9.2 Push Operations

```c
// Push a handle onto the list.
// Handle's refcount is NOT incremented - list just stores the reference.

TPipe_Result TPipe_List_pushHandle(TPipe_ListHandle list, TPipe_Handle handle);
```

### 9.3 Query Operations

```c
TPipe_Handle TPipe_List_getHandle(TPipe_ListHandle list, int32_t index);
```

### 9.4 Batch Release Helper

```c
// Release all handles stored in this list (decrements each handle's refcount).
// After this call, list is empty but handle is still valid.

TPipe_Result TPipe_List_releaseAllHandles(TPipe_ListHandle list);
```

---

## 10. TPipe_MapHandle Overview

`TPipe_MapHandle` is a key-value map container. All keys are strings. Values can be various types.

### 10.1 Value Types

```c
typedef enum {
    TPIPE_MAP_STRING,   // value is const char*
    TPIPE_MAP_INT64,     // value is int64_t
    TPIPE_MAP_FLOAT64,   // value is double
    TPIPE_MAP_HANDLE     // value is TPipe_Handle
} TPipe_MapValueType;
```

---

## 11. Map Lifecycle Functions

### 11.1 Creation

```c
TPipe_MapHandle TPipe_Map_create(void);
// Creates an empty map with TPIPE_MAP_STRING value type (default)
```

### 11.2 Destruction

```c
// Decrement refcount by 1.
// Does NOT release handles stored as values - caller must release separately.

TPipe_Result TPipe_Map_release(TPipe_MapHandle map);
```

---

## 12. String Map Operations

### 12.1 Put/Get

```c
// Insert or update a string value for a key.
// Both key and value are copied by TPipe.

TPipe_Result TPipe_Map_putString(TPipe_MapHandle map,
                                  const char* key,
                                  const char* value);


// Get string value for key.
// Returns pointer to internal storage (do not free).
// Returns NULL if key not found.

const char* TPipe_Map_getString(TPipe_MapHandle map, const char* key);


// Check if key exists.

int TPipe_Map_hasKey(TPipe_MapHandle map, const char* key);


// Remove a key-value pair from the map.

TPipe_Result TPipe_Map_remove(TPipe_MapHandle map, const char* key);


// Get number of entries in map.

int32_t TPipe_Map_size(TPipe_MapHandle map);


// Check if map is empty.

int TPipe_Map_isEmpty(TPipe_MapHandle map);


// Clear all entries from map.

TPipe_Result TPipe_Map_clear(TPipe_MapHandle map);
```

### 12.2 Key Iteration

```c
// Get all keys as a ListHandle (caller must release the list).
// Key order is undefined.

TPipe_ListHandle TPipe_Map_getKeys(TPipe_MapHandle map);
```

---

## 13. Handle Map Operations

### 13.1 Put/Get

```c
// Insert or update a handle value for a key.
// Refcount of stored handle is NOT incremented.

TPipe_Result TPipe_Map_putHandle(TPipe_MapHandle map,
                                 const char* key,
                                 TPipe_Handle value);


// Get handle value for key.

TPipe_Handle TPipe_Map_getHandle(TPipe_MapHandle map, const char* key);
```

### 13.2 Batch Release Helper

```c
// Release all handles stored as values in this map.
// After this call, map is empty but still valid.

TPipe_Result TPipe_Map_releaseAllHandles(TPipe_MapHandle map);
```

---

## 14. Error Handling

All functions return `TPipe_Result`:

| Error | When |
|-------|------|
| `TPIPE_OK` | Success |
| `TPIPE_ERR_INVALID_HANDLE` | List/map handle is invalid |
| `TPIPE_ERR_INVALID_ARGUMENT` | NULL pointer, invalid element type |
| `TPIPE_ERR_TYPE_MISMATCH` | Operation on wrong element type (e.g., pushString on Int32 list) |

---

## 15. Example Usage

```c
// Create a list of strings
TPipe_ListHandle names = TPipe_List_createString();
TPipe_List_pushString(names, "Alice");
TPipe_List_pushString(names, "Bob");
TPipe_List_pushString(names, "Charlie");

printf("Count: %d\n", TPipe_List_size(names));
printf("First: %s\n", TPipe_List_getString(names, 0));

// Create a map of string -> handle
TPipe_MapHandle configs = TPipe_Map_create();
TPipe_Map_putString(configs, "region", "us-east-1");
TPipe_Map_putString(configs, "model", "anthropic.claude-3-sonnet-20240229-v1:0");

// Clean up
TPipe_Map_release(configs);
TPipe_List_release(names);
```

---

## 16. Implementation Checklist

### List Functions
| Function | Status |
|----------|--------|
| `TPipe_List_create()` | ☐ TODO |
| `TPipe_List_release()` | ☐ TODO |
| `TPipe_List_createString()` | ☐ TODO |
| `TPipe_List_pushString()` / `pushStringN()` | ☐ TODO |
| `TPipe_List_size()` | ☐ TODO |
| `TPipe_List_getString()` | ☐ TODO |
| `TPipe_List_isEmpty()` | ☐ TODO |
| `TPipe_List_createInt32()` | ☐ TODO |
| `TPipe_List_pushInt32()` / `getInt32()` | ☐ TODO |
| `TPipe_List_createInt64()` | ☐ TODO |
| `TPipe_List_pushInt64()` / `getInt64()` | ☐ TODO |
| `TPipe_List_createFloat32()` | ☐ TODO |
| `TPipe_List_pushFloat32()` / `getFloat32()` | ☐ TODO |
| `TPipe_List_createFloat64()` | ☐ TODO |
| `TPipe_List_pushFloat64()` / `getFloat64()` | ☐ TODO |
| `TPipe_List_createBool()` | ☐ TODO |
| `TPipe_List_pushBool()` / `getBool()` | ☐ TODO |
| `TPipe_List_createHandle()` | ☐ TODO |
| `TPipe_List_pushHandle()` / `getHandle()` | ☐ TODO |
| `TPipe_List_releaseAllHandles()` | ☐ TODO |

### Map Functions
| Function | Status |
|----------|--------|
| `TPipe_Map_create()` | ☐ TODO |
| `TPipe_Map_release()` | ☐ TODO |
| `TPipe_Map_putString()` / `getString()` | ☐ TODO |
| `TPipe_Map_hasKey()` | ☐ TODO |
| `TPipe_Map_remove()` | ☐ TODO |
| `TPipe_Map_size()` | ☐ TODO |
| `TPipe_Map_isEmpty()` | ☐ TODO |
| `TPipe_Map_clear()` | ☐ TODO |
| `TPipe_Map_getKeys()` | ☐ TODO |
| `TPipe_Map_putHandle()` / `getHandle()` | ☐ TODO |
| `TPipe_Map_releaseAllHandles()` | ☐ TODO |

**Note:** `TPipe_ListHandle` and `TPipe_MapHandle` are C handle types that do not have Kotlin implementations in `src/main/kotlin` — they are generated via GraalVM native image compilation. See `graalvm-abi-bootstrap-plan.md` for the native image entry point generation process.

---

*Next spec: graalvm-abi-distribution-grid-envelope.md (Group 4)*