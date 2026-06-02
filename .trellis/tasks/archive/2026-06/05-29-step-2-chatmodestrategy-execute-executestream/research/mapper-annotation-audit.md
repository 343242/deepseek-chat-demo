# Research: MyBatis Mapper Annotation-to-XML Audit

- **Query**: Audit all MyBatis mapper interfaces for annotation-based SQL (`@Select`, `@Update`, `@Insert`, `@Delete`) and existing XML mapper coverage; cross-reference to identify conversion targets and conflicts.
- **Scope**: internal
- **Date**: 2026-05-29

---

## Configuration

MyBatis-Plus is configured with mapper XML scanning in `application-dev.yml:175` and `application-stable.yml:106`:

```yaml
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

All XML files under `src/main/resources/mapper/` are picked up automatically.

---

## Existing XML Mapper Files (10 files)

All located under `src/main/resources/mapper/`:

| XML File | Namespace | SQL Statements Defined | Status |
|---|---|---|---|
| `AgentEventMapper.xml` | `com.smart.rag.agent.event.AgentEventMapper` | **None** (empty `<mapper>` tag) | Stub only |
| `ModelParamsMapper.xml` | `com.smart.rag.chat.mapper.ModelParamsMapper` | `selectByModelId`, `selectAllOrdered`, `deleteByModelId` | Complete |
| `RagDocumentMapper.xml` | `com.smart.rag.rag.mapper.RagDocumentMapper` | **None** (empty `<mapper>` tag) | Stub only |
| `SysPermissionMapper.xml` | `com.smart.rag.user.mapper.SysPermissionMapper` | `selectAllOrdered`, `selectByPermissionName`, `selectByResourceKey` | Complete |
| `SysRoleMapper.xml` | `com.smart.rag.user.mapper.SysRoleMapper` | `selectAllOrdered`, `selectByRoleName`, `selectByIds` | Complete |
| `SysRolePermissionMapper.xml` | `com.smart.rag.user.mapper.SysRolePermissionMapper` | `selectPermissionsByRoleIds`, `deleteByRoleId`, `batchInsert` | Complete |
| `SysUserMapper.xml` | `com.smart.rag.user.mapper.SysUserMapper` | `selectByUsername`, `selectActiveById`, `selectByEmailExcludingId` | Complete |
| `SysUserRoleMapper.xml` | `com.smart.rag.user.mapper.SysUserRoleMapper` | `selectUserIdsByRoleId`, `deleteByUserId`, `deleteByRoleId`, `batchInsert` | Complete |
| `SystemPromptMapper.xml` | `com.smart.rag.chat.mapper.SystemPromptMapper` | `selectByModelId`, `selectAllOrdered`, `deleteByModelId` | Complete |
| `TokenUsageMapper.xml` | `com.smart.rag.chat.mapper.TokenUsageMapper` | `selectByConversationId`, `selectByModelAndUserPrefix`, `aggregateByModel`, `aggregateByConversation`, `aggregateByModelForUser`, `aggregateByUserConversations` | Complete |

---

## Mapper Interfaces Without XML Files (5 mappers)

These mapper interfaces have **no corresponding XML file** in `src/main/resources/mapper/`:

| Mapper Interface | Path | Has Annotations? |
|---|---|---|
| `ConversationMapper` | `src/main/java/com/smart/rag/conversation/mapper/ConversationMapper.java` | Yes (5 `@Update`) |
| `MessageMapper` | `src/main/java/com/smart/rag/conversation/mapper/MessageMapper.java` | Yes (5 `@Select`) |
| `TeamMapper` | `src/main/java/com/smart/rag/team/mapper/TeamMapper.java` | Yes (1 `@Select`) |
| `TeamUploadApprovalMapper` | `src/main/java/com/smart/rag/team/mapper/TeamUploadApprovalMapper.java` | No (empty, uses only BaseMapper) |
| `TeamMemberMapper` | `src/main/java/com/smart/rag/team/mapper/TeamMemberMapper.java` | Yes (3 `@Select`) |

---

## Per-Mapper Detail: Annotation-Based SQL Inventory

### 1. `AgentEventMapper` (has XML stub -- empty)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `selectBySessionIdOrderByPriorityLimited` | `@Select` | Static SELECT | :30 |
| `selectBySessionIdOrderByPriority` | `@Select` | Static SELECT | :40 |
| `searchBySessionAndUserAndQuery` | `@Select("<script>...")` | Dynamic SELECT (XML script) | :55 |
| `deleteOlderThan` | `@Delete` | Static DELETE | :69 |

**XML file**: `src/main/resources/mapper/AgentEventMapper.xml` exists but is **empty**.
**Conflict check**: No conflicts (XML has no statements).
**Action**: Convert all 4 annotated methods to the existing XML stub.

---

### 2. `ConversationMapper` (no XML)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `incrementMessageCount` | `@Update` | Static UPDATE (multi-line concat) | :20 |
| `updateTitleIfFirst` | `@Update` | Static UPDATE (CAS guard) | :30 |
| `updateTitle` | `@Update` | Static UPDATE | :40 |
| `updateStatus` | `@Update` | Static UPDATE | :49 |
| `updatePinned` | `@Update` | Static UPDATE | :55 |

**XML file**: None.
**Action**: Create `ConversationMapper.xml` and convert all 5 methods.

---

### 3. `MessageMapper` (no XML)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `selectAllByConversationId` | `@Select` | Static SELECT | :20 |
| `selectRootMessages` | `@Select` | Static SELECT | :26 |
| `selectChildren` | `@Select` | Static SELECT | :33 |
| `selectLatestAssistant` | `@Select` | Static SELECT | :41 |
| `countByConversationId` | `@Select` | Static SELECT | :48 |

**XML file**: None.
**Action**: Create `MessageMapper.xml` and convert all 5 methods.

---

### 4. `RagDocumentMapper` (has XML stub -- empty)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `selectFileSizeSum` | `@Select` | Static SELECT | :19 |
| `updateGroupId` | `@Update` | Static UPDATE | :27 |
| `updateGroupIdCas` | `@Update` | Static UPDATE (CAS guard) | :34 |
| `updateSupersededByOnly` | `@Update` | Static UPDATE | :42 |
| `updateGroupIdAndVersion` | `@Update` | Static UPDATE | :48 |
| `updateSuperseded` | `@Update` | Static UPDATE | :54 |
| `findStaleSupersededTargets` | `@Select` | Static SELECT | :60 |

**XML file**: `src/main/resources/mapper/RagDocumentMapper.xml` exists but is **empty**.
**Conflict check**: No conflicts (XML has no statements).
**Action**: Convert all 7 annotated methods to the existing XML stub.

---

### 5. `SysRolePermissionMapper` (has XML -- complete for some methods)

| Method | Annotation | SQL Type | Line | XML? |
|---|---|---|---|---|
| `selectPermissionsByRoleId` | `@Select("""...""")` | Static SELECT (text block) | :15 | **No -- annotation only** |
| `selectPermissionsByRoleIds` | (none) | -- | -- | Yes (`SysRolePermissionMapper.xml`) |
| `deleteByRoleId` | (none) | -- | -- | Yes (`SysRolePermissionMapper.xml`) |
| `batchInsert` | (none) | -- | -- | Yes (`SysRolePermissionMapper.xml`) |

**Conflict check**: `selectPermissionsByRoleId` has `@Select` annotation but NO corresponding XML entry. Other 3 methods are XML-only.
**Action**: Move `selectPermissionsByRoleId` from annotation to XML; remove `@Select` import from Java.

---

### 6. `SysUserRoleMapper` (has XML -- partial overlap)

| Method | Annotation | SQL Type | Line | XML? |
|---|---|---|---|---|
| `selectRoleIdsByUserId` | `@Select` | Static SELECT | :14 | **No -- annotation only** |
| `selectUserIdsByRoleId` | (none) | -- | -- | Yes (`SysUserRoleMapper.xml`) |
| `deleteByUserId` | (none) | -- | -- | Yes (`SysUserRoleMapper.xml`) |
| `deleteByRoleId` | (none) | -- | -- | Yes (`SysUserRoleMapper.xml`) |
| `batchInsert` | (none) | -- | -- | Yes (`SysUserRoleMapper.xml`) |

**Conflict check**: `selectRoleIdsByUserId` has `@Select` annotation but NO corresponding XML entry. Other 4 methods are XML-only.
**Action**: Move `selectRoleIdsByUserId` from annotation to XML; remove `@Select` import from Java.

---

### 7. `TeamMapper` (no XML)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `selectByIdForUpdate` | `@Select` | Static SELECT (FOR UPDATE) | :12 |

**XML file**: None.
**Action**: Create `TeamMapper.xml` and convert 1 method.

---

### 8. `TeamMemberMapper` (no XML)

| Method | Annotation | SQL Type | Line |
|---|---|---|---|
| `selectByTeamAndUser` | `@Select` | Static SELECT | :15 |
| `selectLatestByTeamAndUser` | `@Select` | Static SELECT | :21 |
| `selectMemberCountByTeamIds` | `@Select("<script>...")` | Dynamic SELECT (XML script with `<foreach>`) | :29 |

**XML file**: None.
**Action**: Create `TeamMemberMapper.xml` and convert all 3 methods. Note: `selectMemberCountByTeamIds` already uses XML script syntax inside the annotation -- it will translate naturally to XML.

---

### 9-15. Already Fully XML Mappers (no annotations)

These mappers have **zero annotation-based SQL** and all methods are defined in XML:

| Mapper | Java File | XML File | Methods in XML |
|---|---|---|---|
| `ModelParamsMapper` | `chat/mapper/ModelParamsMapper.java` | `ModelParamsMapper.xml` | 3 (`selectByModelId`, `selectAllOrdered`, `deleteByModelId`) |
| `SysPermissionMapper` | `user/mapper/SysPermissionMapper.java` | `SysPermissionMapper.xml` | 3 (`selectAllOrdered`, `selectByPermissionName`, `selectByResourceKey`) |
| `SysRoleMapper` | `user/mapper/SysRoleMapper.java` | `SysRoleMapper.xml` | 3 (`selectAllOrdered`, `selectByRoleName`, `selectByIds`) |
| `SysUserMapper` | `user/mapper/SysUserMapper.java` | `SysUserMapper.xml` | 3 (`selectByUsername`, `selectActiveById`, `selectByEmailExcludingId`) |
| `SystemPromptMapper` | `chat/mapper/SystemPromptMapper.java` | `SystemPromptMapper.xml` | 3 (`selectByModelId`, `selectAllOrdered`, `deleteByModelId`) |
| `TokenUsageMapper` | `chat/mapper/TokenUsageMapper.java` | `TokenUsageMapper.xml` | 6 (all aggregate + select methods) |

**Action**: None needed (already fully XML-based).

---

### 16. Empty Mapper (BaseMapper only)

| Mapper | Java File | Notes |
|---|---|---|
| `TeamUploadApprovalMapper` | `team/mapper/TeamUploadApprovalMapper.java` | Empty interface, only extends `BaseMapper<TeamUploadApproval>`. No custom methods. No XML needed. |

**Action**: None needed.

---

## Conflict Analysis

**No annotation-vs-XML conflicts detected.** Every method is defined by exactly one source (either annotation OR XML, never both). The two mappers with partial XML coverage (`SysRolePermissionMapper` and `SysUserRoleMapper`) have their annotation methods and XML methods defined on **different method names**, so there is no duplicate statement ID conflict.

---

## Summary: Conversion Action Plan

### Priority 1: Fill existing empty XML stubs (2 mappers, 11 methods)

| XML File to Fill | Mapper | Methods to Convert |
|---|---|---|
| `AgentEventMapper.xml` | `AgentEventMapper` | 4 methods (3 `@Select`, 1 `@Delete`) |
| `RagDocumentMapper.xml` | `RagDocumentMapper` | 7 methods (2 `@Select`, 5 `@Update`) |

### Priority 2: Create new XML files (4 mappers, 14 methods)

| New XML File | Mapper | Methods to Convert |
|---|---|---|
| `ConversationMapper.xml` | `ConversationMapper` | 5 `@Update` methods |
| `MessageMapper.xml` | `MessageMapper` | 5 `@Select` methods |
| `TeamMapper.xml` | `TeamMapper` | 1 `@Select` method |
| `TeamMemberMapper.xml` | `TeamMemberMapper` | 3 `@Select` methods (1 dynamic `<script>`) |

### Priority 3: Move straggler annotations to existing XML (2 mappers, 2 methods)

| XML File to Update | Mapper | Methods to Move |
|---|---|---|
| `SysRolePermissionMapper.xml` | `SysRolePermissionMapper` | `selectPermissionsByRoleId` (1 `@Select` text block) |
| `SysUserRoleMapper.xml` | `SysUserRoleMapper` | `selectRoleIdsByUserId` (1 `@Select`) |

### Grand Total

- **27 annotation-based SQL statements** across 8 mapper interfaces
- **10 XML files** already exist (2 empty stubs, 8 complete)
- **4 new XML files** need to be created
- **2 existing XML files** need 1 additional method each
- **2 empty XML stubs** need to be populated
- **6 mappers** already fully XML (no action needed)
- **1 mapper** is empty/BaseMapper-only (no action needed)

---

## Caveats / Notes

- `AgentEventMapper.searchBySessionAndUserAndQuery` (line 55) and `TeamMemberMapper.selectMemberCountByTeamIds` (line 29) use `<script>` inside `@Select` annotations. These are already written in XML syntax and will convert trivially.
- `SysRolePermissionMapper.selectPermissionsByRoleId` (line 15) uses Java text block (`"""..."""`). Must ensure the XML preserves the multi-line SQL formatting.
- All mappers extend `BaseMapper<T>` from MyBatis-Plus, which provides standard CRUD methods (selectById, insert, updateById, deleteById, selectList, etc.) automatically -- these do NOT need XML and are not part of this conversion.
- The `mybatis-plus.mapper-locations` pattern `classpath*:/mapper/**/*.xml` will automatically pick up any new XML files placed under `src/main/resources/mapper/`.
