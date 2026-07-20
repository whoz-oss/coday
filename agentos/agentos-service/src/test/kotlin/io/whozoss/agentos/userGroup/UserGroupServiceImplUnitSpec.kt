package io.whozoss.agentos.userGroup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID
import java.util.UUID.randomUUID

class UserGroupServiceImplUnitSpec :
    StringSpec({

        val namespaceId = randomUUID()
        val externalId = "federation-test"

        val namespace =
            Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                externalId = externalId,
            )

        fun agentConfig(
            id: UUID = randomUUID(),
            nsId: UUID = namespaceId,
        ) = AgentConfig(
            metadata = EntityMetadata(id = id),
            namespaceId = nsId,
            name = "agent-$id",
        )

        fun makeUser(externalId: String) =
            User(
                metadata = EntityMetadata(id = randomUUID()),
                externalId = externalId,
                isAdmin = false,
            )

        fun buildService(
            userGroupRepository: UserGroupRepository = mockk(relaxed = true),
            namespaceService: NamespaceService = mockk(),
            agentConfigRepository: AgentConfigRepository = mockk(),
            userService: UserService = mockk(relaxed = true),
            permissionService: PermissionService = mockk(relaxed = true),
        ) = UserGroupServiceImpl(
            userGroupRepository,
            namespaceService,
            agentConfigRepository,
            userService,
            permissionService,
        )

        // -------------------------------------------------------------------------
        // createFromRequest — agent validation
        // -------------------------------------------------------------------------

        "createFromRequest with valid agents calls addAgents" {
            val agentId1 = randomUUID()
            val agentId2 = randomUUID()
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team A")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team A",
                    agentIds = listOf(agentId1, agentId2),
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { agentConfigRepository.findByIds(setOf(agentId1, agentId2)) } returns
                listOf(agentConfig(agentId1), agentConfig(agentId2))
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult

            val service = buildService(userGroupRepository, namespaceService, agentConfigRepository)
            val result =
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespaceId,
                        name = "Team A",
                        agentIds = setOf(agentId1, agentId2),
                    ),
                )

            verify(exactly = 1) {
                userGroupRepository.addAgents(
                    groupId,
                    match { ids ->
                        ids.toSet() == setOf(agentId1, agentId2)
                    },
                )
            }
            result.agentIds shouldContainExactlyInAnyOrder listOf(agentId1, agentId2)
        }

        "createFromRequest with no agents skips addAgents" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team B")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team B",
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceId(namespaceId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository, namespaceService, agentConfigRepository)
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Team B",
                ),
            )

            verify(exactly = 0) { userGroupRepository.addAgents(any(), any()) }
        }

        "createFromRequest throws 422 when an agentId belongs to a different namespace" {
            val agentId = randomUUID()
            val otherNamespaceId = randomUUID()

            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { agentConfigRepository.findByIds(setOf(agentId)) } returns
                listOf(agentConfig(agentId, nsId = otherNamespaceId))

            val service =
                buildService(namespaceService = namespaceService, agentConfigRepository = agentConfigRepository)

            shouldThrow<UnprocessableEntityException> {
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespaceId,
                        name = "Team C",
                        agentIds = setOf(agentId),
                    ),
                )
            }
        }

        "createFromRequest throws 422 when an agentId does not exist" {
            val agentId = randomUUID()

            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { agentConfigRepository.findByIds(setOf(agentId)) } returns emptyList()

            val service =
                buildService(namespaceService = namespaceService, agentConfigRepository = agentConfigRepository)

            shouldThrow<UnprocessableEntityException> {
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespaceId,
                        name = "Team D",
                        agentIds = setOf(agentId),
                    ),
                )
            }
        }

        // -------------------------------------------------------------------------
        // createFromRequest — user linking
        // -------------------------------------------------------------------------

        "createFromRequest creates only missing users before calling addUsers" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team E")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team E",
                    userCount = 2,
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val userService = mockk<UserService>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            // alice already exists, bob does not
            every { userService.findByExternalIds(setOf("alice@example.com", "bob@example.com")) } returns
                listOf(makeUser("alice@example.com"))
            every { userService.createByExternalIds(setOf("bob@example.com")) } returns
                listOf(makeUser("bob@example.com"))

            val service = buildService(userGroupRepository, namespaceService, userService = userService)
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Team E",
                    userExternalIdsToAdd = setOf("alice@example.com", "bob@example.com"),
                ),
            )

            verify(exactly = 1) { userService.createByExternalIds(setOf("bob@example.com")) }
            verify(exactly = 1) {
                userGroupRepository.addUsers(
                    groupId,
                    match { ids -> ids.toSet() == setOf("alice@example.com", "bob@example.com") },
                )
            }
        }

        "createFromRequest with userExternalIds calls addUsers" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team E")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team E",
                    userCount = 2,
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult

            val service = buildService(userGroupRepository = userGroupRepository, namespaceService = namespaceService)
            val result =
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespaceId,
                        name = "Team E",
                        userExternalIdsToAdd = setOf("alice@example.com", "bob@example.com"),
                    ),
                )

            verify(exactly = 1) {
                userGroupRepository.addUsers(
                    groupId,
                    match { ids -> ids.toSet() == setOf("alice@example.com", "bob@example.com") },
                )
            }
            result.userCount shouldBe 2
        }

        // -------------------------------------------------------------------------
        // updateFromRequest
        // -------------------------------------------------------------------------

        "updateFromRequest renames the group, replaces agents, adds and removes users" {
            val groupId = randomUUID()
            val agentId = randomUUID()
            val existing =
                UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Old Name")
            val updated = existing.copy(name = "New Name")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "New Name",
                    agentIds = listOf(agentId),
                    userCount = 1,
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val agentConfigRepository = mockk<AgentConfigRepository>()
            val userService = mockk<UserService>()

            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { agentConfigRepository.findByIds(setOf(agentId)) } returns listOf(agentConfig(agentId))
            every { userGroupRepository.save(any()) } returns updated
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            // alice is being added and does not exist yet
            every { userService.findByExternalIds(setOf("alice@example.com")) } returns emptyList()
            every { userService.createByExternalIds(setOf("alice@example.com")) } returns listOf(makeUser("alice@example.com"))

            val service =
                buildService(
                    userGroupRepository,
                    agentConfigRepository = agentConfigRepository,
                    userService = userService,
                )
            val result =
                service.updateFromRequest(
                    groupId,
                    UserGroupUpdateRequest(
                        name = "New Name",
                        agentIds = setOf(agentId),
                        userExternalIdsToAdd = setOf("alice@example.com"),
                        userExternalIdsToRemove = setOf("bob@example.com"),
                    ),
                )

            verifyOrder {
                userGroupRepository.save(match { it.name == "New Name" })
                userGroupRepository.removeAllAgents(groupId)
                userGroupRepository.addAgents(groupId, setOf(agentId))
                userService.createByExternalIds(setOf("alice@example.com"))
                userGroupRepository.addUsers(groupId, setOf("alice@example.com"))
                userGroupRepository.removeUsers(groupId, setOf("bob@example.com"))
            }
            result.name shouldBe "New Name"
            result.agentIds shouldContainExactlyInAnyOrder setOf(agentId)
        }

        "updateFromRequest with empty agentIds removes all agents" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult

            val service = buildService(userGroupRepository)
            service.updateFromRequest(groupId, UserGroupUpdateRequest(name = "Team"))

            verify(exactly = 1) { userGroupRepository.removeAllAgents(groupId) }
            verify(exactly = 0) { userGroupRepository.addAgents(any(), any()) }
        }

        "updateFromRequest throws 422 when same externalId appears in added and removed" {
            val groupId = randomUUID()
            val service = buildService()

            shouldThrow<UnprocessableEntityException> {
                service.updateFromRequest(
                    groupId,
                    UserGroupUpdateRequest(
                        name = "Team",
                        userExternalIdsToAdd = setOf("alice@example.com", "carol@example.com"),
                        userExternalIdsToRemove = setOf("carol@example.com", "bob@example.com"),
                    ),
                )
            }
        }

        "updateFromRequest throws 404 when group does not exist" {
            val groupId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns emptyList()

            val service = buildService(userGroupRepository)

            shouldThrow<ResourceNotFoundException> {
                service.updateFromRequest(groupId, UserGroupUpdateRequest(name = "Ghost"))
            }
        }

        "updateFromRequest throws 422 when an agentId does not belong to the namespace" {
            val groupId = randomUUID()
            val agentId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val agentConfigRepository = mockk<AgentConfigRepository>()
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { agentConfigRepository.findByIds(setOf(agentId)) } returns
                listOf(
                    agentConfig(
                        agentId,
                        nsId = randomUUID(),
                    ),
                )

            val service = buildService(userGroupRepository, agentConfigRepository = agentConfigRepository)

            shouldThrow<UnprocessableEntityException> {
                service.updateFromRequest(groupId, UserGroupUpdateRequest(name = "Team", agentIds = setOf(agentId)))
            }
        }

        // -------------------------------------------------------------------------
        // findGroupsByUserExternalIdsVisibleToUser
        // -------------------------------------------------------------------------

        "findGroupsByUserExternalIdsVisibleToUser returns all groups for admin" {
            val groupId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>()
            val adminUser =
                User(metadata = EntityMetadata(id = randomUUID()), externalId = "admin@example.com", isAdmin = true)
            val groups = mapOf("alice@example.com" to listOf(UserGroupSummary(id = groupId, name = "Team A")))

            every { userGroupRepository.findGroupsByUserExternalIds(listOf("alice@example.com")) } returns groups

            val service = buildService(userGroupRepository = userGroupRepository)
            val result = service.findGroupsByUserExternalIdsVisibleToUser(listOf("alice@example.com"), adminUser)

            result shouldBe groups
        }

        "findGroupsByUserExternalIdsVisibleToUser filters groups by permission for non-admin" {
            val visibleGroupId = randomUUID()
            val hiddenGroupId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>()
            val permService = mockk<PermissionService>()
            val regularUser =
                User(metadata = EntityMetadata(id = randomUUID()), externalId = "user@example.com", isAdmin = false)
            val groups =
                mapOf(
                    "alice@example.com" to
                        listOf(
                            UserGroupSummary(id = visibleGroupId, name = "Visible"),
                            UserGroupSummary(id = hiddenGroupId, name = "Hidden"),
                        ),
                )

            every { userGroupRepository.findGroupsByUserExternalIds(listOf("alice@example.com")) } returns groups
            every {
                permService.filterVisibleIds(
                    userId = regularUser.id.toString(),
                    entityType = EntityType.USER_GROUP,
                    ids = setOf(visibleGroupId.toString(), hiddenGroupId.toString()),
                    action = Action.READ,
                )
            } returns setOf(visibleGroupId.toString())

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permService)
            val result = service.findGroupsByUserExternalIdsVisibleToUser(listOf("alice@example.com"), regularUser)

            result shouldBe
                mapOf(
                    "alice@example.com" to
                        listOf(
                            UserGroupSummary(
                                id = visibleGroupId,
                                name = "Visible",
                            ),
                        ),
                )
        }

        "findGroupsByUserExternalIdsVisibleToUser removes entry when all groups are filtered out" {
            val hiddenGroupId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>()
            val permService = mockk<PermissionService>()
            val regularUser =
                User(metadata = EntityMetadata(id = randomUUID()), externalId = "user@example.com", isAdmin = false)
            val groups =
                mapOf(
                    "alice@example.com" to listOf(UserGroupSummary(id = hiddenGroupId, name = "Hidden")),
                )

            every { userGroupRepository.findGroupsByUserExternalIds(listOf("alice@example.com")) } returns groups
            every {
                permService.filterVisibleIds(
                    userId = regularUser.id.toString(),
                    entityType = EntityType.USER_GROUP,
                    ids = setOf(hiddenGroupId.toString()),
                    action = Action.READ,
                )
            } returns emptySet()

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permService)
            val result = service.findGroupsByUserExternalIdsVisibleToUser(listOf("alice@example.com"), regularUser)

            result shouldBe emptyMap()
        }

        "findGroupsByUserExternalIdsVisibleToUser forwards namespaceId to repository when provided" {
            val groupId = randomUUID()
            val scopedNamespaceId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>()
            val adminUser =
                User(metadata = EntityMetadata(id = randomUUID()), externalId = "admin@example.com", isAdmin = true)
            val groups = mapOf("alice@example.com" to listOf(UserGroupSummary(id = groupId, name = "Team A")))

            every {
                userGroupRepository.findGroupsByUserExternalIds(
                    listOf("alice@example.com"),
                    scopedNamespaceId,
                )
            } returns groups

            val service = buildService(userGroupRepository = userGroupRepository)
            val result =
                service.findGroupsByUserExternalIdsVisibleToUser(
                    listOf("alice@example.com"),
                    adminUser,
                    namespaceId = scopedNamespaceId,
                )

            result shouldBe groups
            verify(exactly = 1) {
                userGroupRepository.findGroupsByUserExternalIds(
                    listOf("alice@example.com"),
                    scopedNamespaceId,
                )
            }
        }

        "findGroupsByUserExternalIdsVisibleToUser forwards null namespaceId to repository when not provided" {
            val groupId = randomUUID()
            val userGroupRepository = mockk<UserGroupRepository>()
            val adminUser =
                User(metadata = EntityMetadata(id = randomUUID()), externalId = "admin@example.com", isAdmin = true)
            val groups = mapOf("alice@example.com" to listOf(UserGroupSummary(id = groupId, name = "Team A")))

            every { userGroupRepository.findGroupsByUserExternalIds(listOf("alice@example.com"), null) } returns groups

            val service = buildService(userGroupRepository = userGroupRepository)
            val result =
                service.findGroupsByUserExternalIdsVisibleToUser(
                    listOf("alice@example.com"),
                    adminUser,
                )

            result shouldBe groups
            verify(exactly = 1) { userGroupRepository.findGroupsByUserExternalIds(listOf("alice@example.com"), null) }
        }

        "createFromRequest with no userExternalIds skips addUsers" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team F")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team F",
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceId(namespaceId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository = userGroupRepository, namespaceService = namespaceService)
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Team F",
                ),
            )

            verify(exactly = 0) { userGroupRepository.addUsers(any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Member roles (getMembers / adminExternalIds)
        // -------------------------------------------------------------------------

        "getMembers delegates to the repository" {
            val groupId = randomUUID()
            val members =
                listOf(
                    UserGroupMember(
                        userId = randomUUID(),
                        externalId = "alice@example.com",
                        role = "ADMIN",
                        email = "alice@example.com",
                        firstname = "Alice",
                        lastname = null,
                    ),
                )
            val userGroupRepository = mockk<UserGroupRepository>()
            every { userGroupRepository.findMembers(groupId) } returns members

            val service = buildService(userGroupRepository = userGroupRepository)

            service.getMembers(groupId) shouldBe members
            verify(exactly = 1) { userGroupRepository.findMembers(groupId) }
        }

        "createFromRequest promotes admins through the permission share batch" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 2,
                )
            val alice = makeUser("alice@example.com")
            val bob = makeUser("bob@example.com")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val userService = mockk<UserService>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userService.findByExternalIds(setOf("alice@example.com", "bob@example.com")) } returns
                listOf(alice, bob)

            val service =
                buildService(
                    userGroupRepository = userGroupRepository,
                    namespaceService = namespaceService,
                    userService = userService,
                    permissionService = permissionService,
                )
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Team",
                    userExternalIdsToAdd = setOf("alice@example.com", "bob@example.com"),
                    adminExternalIds = setOf("alice@example.com"),
                ),
            )

            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.USER_GROUP,
                    groupId.toString(),
                    listOf(alice.id.toString() to PermissionRelation.ADMIN),
                )
            }
        }

        "createFromRequest throws 422 when an admin is not among the members" {
            val namespaceService = mockk<NamespaceService> { every { getById(namespaceId) } returns namespace }
            val service = buildService(namespaceService = namespaceService)

            shouldThrow<UnprocessableEntityException> {
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespaceId,
                        name = "Team",
                        userExternalIdsToAdd = setOf("alice@example.com"),
                        adminExternalIds = setOf("carol@example.com"),
                    ),
                )
            }
        }

        "updateFromRequest applies only actual role changes through the permission share batch" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 3,
                )
            val alice = makeUser("alice@example.com") // MEMBER promoted to ADMIN
            val carol = makeUser("carol@example.com") // ADMIN dropped from the set, demoted
            val dave = makeUser("dave@example.com") // ADMIN staying ADMIN, untouched

            fun member(user: User, role: String) =
                UserGroupMember(
                    userId = user.id,
                    externalId = user.externalId,
                    role = role,
                    email = user.externalId,
                    firstname = null,
                    lastname = null,
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userGroupRepository.findMembers(groupId) } returns
                listOf(member(alice, "MEMBER"), member(carol, "ADMIN"), member(dave, "ADMIN"))

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permissionService)
            service.updateFromRequest(
                groupId,
                UserGroupUpdateRequest(
                    name = "Team",
                    adminExternalIds = setOf("alice@example.com", "dave@example.com"),
                ),
            )

            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.USER_GROUP,
                    groupId.toString(),
                    listOf(
                        alice.id.toString() to PermissionRelation.ADMIN,
                        carol.id.toString() to PermissionRelation.MEMBER,
                    ),
                )
            }
        }

        "createFromRequest promotes an admin that is created on the fly" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 1,
                )
            val newcomer = makeUser("newcomer@example.com")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val userService = mockk<UserService>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)

            every { namespaceService.getById(namespaceId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userService.findByExternalIds(setOf("newcomer@example.com")) } returns emptyList()
            every { userService.createByExternalIds(setOf("newcomer@example.com")) } returns listOf(newcomer)

            val service =
                buildService(
                    userGroupRepository = userGroupRepository,
                    namespaceService = namespaceService,
                    userService = userService,
                    permissionService = permissionService,
                )
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Team",
                    userExternalIdsToAdd = setOf("newcomer@example.com"),
                    adminExternalIds = setOf("newcomer@example.com"),
                ),
            )

            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.USER_GROUP,
                    groupId.toString(),
                    listOf(newcomer.id.toString() to PermissionRelation.ADMIN),
                )
            }
        }

        "updateFromRequest promotes an admin that is among the newly added users" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 1,
                )
            val newcomer = makeUser("newcomer@example.com")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val userService = mockk<UserService>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userGroupRepository.findMembers(groupId) } returns emptyList()
            every { userService.findByExternalIds(setOf("newcomer@example.com")) } returns listOf(newcomer)

            val service =
                buildService(
                    userGroupRepository = userGroupRepository,
                    userService = userService,
                    permissionService = permissionService,
                )
            service.updateFromRequest(
                groupId,
                UserGroupUpdateRequest(
                    name = "Team",
                    userExternalIdsToAdd = setOf("newcomer@example.com"),
                    adminExternalIds = setOf("newcomer@example.com"),
                ),
            )

            verify(exactly = 1) {
                permissionService.applyShareBatch(
                    EntityType.USER_GROUP,
                    groupId.toString(),
                    listOf(newcomer.id.toString() to PermissionRelation.ADMIN),
                )
            }
        }

        "updateFromRequest does not demote an admin that is being removed from the group" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 0,
                )
            val carol = makeUser("carol@example.com")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userGroupRepository.findMembers(groupId) } returns
                listOf(
                    UserGroupMember(
                        userId = carol.id,
                        externalId = carol.externalId,
                        role = "ADMIN",
                        email = carol.externalId,
                        firstname = null,
                        lastname = null,
                    ),
                )

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permissionService)
            service.updateFromRequest(
                groupId,
                UserGroupUpdateRequest(
                    name = "Team",
                    userExternalIdsToRemove = setOf("carol@example.com"),
                ),
            )

            // A demotion entry would re-create a MEMBER edge for the user removeUsers just unlinked.
            verify(exactly = 0) { permissionService.applyShareBatch(any(), any(), any()) }
            verify(exactly = 1) { userGroupRepository.removeUsers(groupId, setOf("carol@example.com")) }
            // removeUsers bypasses the permission cache, so the removed group ADMIN's cache must be
            // invalidated explicitly or a stale WRITE/DELETE grant survives until the TTL expires.
            verify(exactly = 1) { permissionService.clearUserCache(carol.id.toString()) }
        }

        "updateFromRequest does not clear the cache for users it did not remove" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Renamed",
                )
            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userGroupRepository.findMembers(groupId) } returns emptyList()

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permissionService)
            service.updateFromRequest(groupId, UserGroupUpdateRequest(name = "Renamed"))

            verify(exactly = 0) { permissionService.clearUserCache(any()) }
        }

        "updateFromRequest skips the permission share batch when no role changes" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team",
                    userCount = 1,
                )
            val alice = makeUser("alice@example.com")

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val permissionService = mockk<PermissionService>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.save(any()) } returns existing
            every { userGroupRepository.findByIdWithDetails(groupId) } returns searchResult
            every { userGroupRepository.findMembers(groupId) } returns
                listOf(
                    UserGroupMember(
                        userId = alice.id,
                        externalId = alice.externalId,
                        role = "ADMIN",
                        email = alice.externalId,
                        firstname = null,
                        lastname = null,
                    ),
                )

            val service = buildService(userGroupRepository = userGroupRepository, permissionService = permissionService)
            service.updateFromRequest(
                groupId,
                UserGroupUpdateRequest(
                    name = "Team",
                    adminExternalIds = setOf("alice@example.com"),
                ),
            )

            verify(exactly = 0) { permissionService.applyShareBatch(any(), any(), any()) }
        }

        "updateFromRequest throws 422 when an admin is not a member" {
            val groupId = randomUUID()
            val existing = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team")
            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            every { userGroupRepository.findByIds(listOf(groupId), withRemoved = true) } returns listOf(existing)
            every { userGroupRepository.findMembers(groupId) } returns emptyList()

            val service = buildService(userGroupRepository)

            shouldThrow<UnprocessableEntityException> {
                service.updateFromRequest(
                    groupId,
                    UserGroupUpdateRequest(name = "Team", adminExternalIds = setOf("carol@example.com")),
                )
            }
        }

        "updateFromRequest throws 422 when an admin is also being removed" {
            val groupId = randomUUID()
            val service = buildService()

            shouldThrow<UnprocessableEntityException> {
                service.updateFromRequest(
                    groupId,
                    UserGroupUpdateRequest(
                        name = "Team",
                        userExternalIdsToRemove = setOf("alice@example.com"),
                        adminExternalIds = setOf("alice@example.com"),
                    ),
                )
            }
        }
    })
