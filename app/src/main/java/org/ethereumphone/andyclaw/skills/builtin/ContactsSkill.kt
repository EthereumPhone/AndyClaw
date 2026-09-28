package org.ethereumphone.andyclaw.skills.builtin

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.contactssdk.ContactsSDK

class ContactsSkill(private val context: Context) : AndyClawSkill {
    override val id = "contacts"
    override val name = "Contacts"

    private val contactsSDK by lazy { ContactsSDK(context) }

    override val baseManifest = SkillManifest(
        description = "Search and view contacts on the device, including Ethereum addresses and ENS names.",
        tools = listOf(
            ToolDefinition(
                name = "search_contacts",
                description = "Search contacts by name. Returns matching contacts with ETH addresses and ENS names.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Search query (name)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("query"))),
                )),
                requiredPermissions = listOf("android.permission.READ_CONTACTS"),
            ),
            ToolDefinition(
                name = "get_contact_details",
                description = "Get full details of a contact by contact ID, including ETH address and ENS name.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contact_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The contact ID"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("contact_id"))),
                )),
                requiredPermissions = listOf("android.permission.READ_CONTACTS"),
            ),
            ToolDefinition(
                name = "get_eth_contacts",
                description = "Get all contacts that have an Ethereum address or ENS name.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
                requiredPermissions = listOf("android.permission.READ_CONTACTS"),
            ),
        ),
        permissions = listOf("android.permission.READ_CONTACTS"),
    )

    override val privilegedManifest = SkillManifest(
        description = "Create and edit contacts with Ethereum data (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "create_contact",
                description = "Create a new contact with name, phone, email, ETH address, and/or ENS name.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Contact display name"),
                        )),
                        "phone" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Phone number"),
                        )),
                        "email" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Email address"),
                        )),
                        "eth_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Ethereum address (0x-prefixed)"),
                        )),
                        "ens_name" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("ENS name (e.g., vitalik.eth)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("name"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.WRITE_CONTACTS"),
            ),
            ToolDefinition(
                name = "update_contact",
                description = "Change an existing contact's name, phone number or email. Replaces the contact's " +
                    "first number or email (or adds one if it has none). Find the contact_id with search_contacts.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contact_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The contact ID"),
                        )),
                        "name" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("New display name"),
                        )),
                        "phone" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("New phone number"),
                        )),
                        "email" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("New email address"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("contact_id"))),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.WRITE_CONTACTS"),
            ),
            ToolDefinition(
                name = "set_eth_address",
                description = "Set or update the ETH address on an existing contact.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contact_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("The contact ID"),
                        )),
                        "eth_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Ethereum address (0x-prefixed, 42 characters)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("contact_id"),
                        JsonPrimitive("eth_address"),
                    )),
                )),
                requiresApproval = true,
                requiredPermissions = listOf("android.permission.WRITE_CONTACTS"),
            ),
        ),
        permissions = listOf("android.permission.WRITE_CONTACTS"),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "search_contacts" -> searchContacts(params)
            "get_contact_details" -> getContactDetails(params)
            "get_eth_contacts" -> getEthContacts()
            "create_contact" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("create_contact requires privileged OS")
                else createContact(params)
            }
            "set_eth_address" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("set_eth_address requires privileged OS")
                else setEthAddress(params)
            }
            "update_contact" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("update_contact requires privileged OS")
                else updateContact(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun searchContacts(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: query")
        return try {
            val contacts = contactsSDK.getContacts()
                .filter { it.displayName.contains(query, ignoreCase = true) }
                .take(20)
            val result = JsonArray(contacts.map { c ->
                buildJsonObject {
                    put("id", c.contactId)
                    put("name", c.displayName)
                    c.phoneNumber?.let { put("phone", it) }
                    c.email?.let { put("email", it) }
                    c.ethAddress?.let { put("eth_address", it) }
                    c.ensName?.let { put("ens_name", it) }
                }
            })
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to search contacts: ${e.message}")
        }
    }

    private fun getContactDetails(params: JsonObject): SkillResult {
        val contactId = params["contact_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contact_id")
        return try {
            val contact = contactsSDK.getContactById(contactId)
                ?: return SkillResult.Error("Contact not found: $contactId")
            val result = buildJsonObject {
                put("id", contact.contactId)
                put("name", contact.displayName)
                contact.phoneNumber?.let { put("phone", it) }
                contact.email?.let { put("email", it) }
                contact.photoUri?.let { put("photo_uri", it) }
                contact.ethAddress?.let { put("eth_address", it) }
                contact.ensName?.let { put("ens_name", it) }
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get contact details: ${e.message}")
        }
    }

    private fun getEthContacts(): SkillResult {
        return try {
            val contacts = contactsSDK.getContactsWithEthData()
            val result = JsonArray(contacts.map { c ->
                buildJsonObject {
                    put("id", c.contactId)
                    put("name", c.displayName)
                    c.ethAddress?.let { put("eth_address", it) }
                    c.ensName?.let { put("ens_name", it) }
                }
            })
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get ETH contacts: ${e.message}")
        }
    }

    private fun createContact(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        val phone = params["phone"]?.jsonPrimitive?.contentOrNull
        val email = params["email"]?.jsonPrimitive?.contentOrNull
        val ethAddress = params["eth_address"]?.jsonPrimitive?.contentOrNull
        val ensName = params["ens_name"]?.jsonPrimitive?.contentOrNull

        return try {
            val contactId = contactsSDK.addContact(
                displayName = name,
                phoneNumber = phone,
                email = email,
                ethAddress = ethAddress,
                ensName = ensName,
            )
            if (contactId != null) {
                SkillResult.Success(buildJsonObject {
                    put("success", true)
                    put("contact_id", contactId)
                    put("name", name)
                }.toString())
            } else {
                SkillResult.Error("Failed to create contact")
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to create contact: ${e.message}")
        }
    }

    /**
     * Rewrites the name, first number and first email of one contact. Without it, "Grace got a
     * new number" went through the Contacts app's editor or, when that failed, through
     * execute_code writing ContactsContract by hand (agentbench contact_edit_number).
     */
    private fun updateContact(params: JsonObject): SkillResult {
        val contactId = params["contact_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return SkillResult.Error("Missing or invalid parameter: contact_id (use the id from search_contacts)")
        val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val phone = params["phone"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val email = params["email"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null && phone == null && email == null) {
            return SkillResult.Error("Nothing to change: give name, phone and/or email")
        }
        return try {
            val resolver = context.contentResolver
            val rawId = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString()), "${ContactsContract.RawContacts._ID} ASC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
                ?: return SkillResult.Error("Contact not found: $contactId")

            val ops = ArrayList<ContentProviderOperation>()
            fun upsert(mimeType: String, column: String, value: String, extra: Map<String, Any> = emptyMap()) {
                val dataId = resolver.query(
                    ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(contactId.toString(), mimeType), "${ContactsContract.Data._ID} ASC",
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
                ops += if (dataId != null) {
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
                        .withValue(column, value)
                        .build()
                } else {
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                        .withValue(column, value)
                        .apply { extra.forEach { (k, v) -> withValue(k, v) } }
                        .build()
                }
            }
            name?.let {
                upsert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it)
            }
            phone?.let {
                upsert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Phone.NUMBER, it,
                    mapOf(ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
            }
            email?.let {
                upsert(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Email.ADDRESS, it,
                    mapOf(ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_HOME))
            }
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)

            // What the contact now says, not what was asked for.
            val after = contactsSDK.getContactById(contactId.toString())
                ?: return SkillResult.Error("Contact $contactId could not be read back after the update")
            val digits = { s: String? -> s.orEmpty().filter(Char::isDigit) }
            val mismatch = listOfNotNull(
                name?.takeIf { after.displayName != it }?.let { "name reads '${after.displayName}'" },
                phone?.takeIf { digits(after.phoneNumber) != digits(it) }?.let { "phone reads '${after.phoneNumber}'" },
                email?.takeIf { !after.email.equals(it, ignoreCase = true) }?.let { "email reads '${after.email}'" },
            )
            if (mismatch.isNotEmpty()) {
                return SkillResult.Error("Update did not take: ${mismatch.joinToString("; ")}")
            }
            SkillResult.Success(buildJsonObject {
                put("success", true)
                put("contact_id", contactId.toString())
                put("name", after.displayName)
                after.phoneNumber?.let { put("phone", it) }
                after.email?.let { put("email", it) }
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to update contact: ${e.message}")
        }
    }

    private fun setEthAddress(params: JsonObject): SkillResult {
        val contactId = params["contact_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contact_id")
        val ethAddress = params["eth_address"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: eth_address")

        return try {
            val success = contactsSDK.setEthAddress(contactId.toLong(), ethAddress)
            if (success) {
                SkillResult.Success(buildJsonObject {
                    put("success", true)
                    put("contact_id", contactId)
                    put("eth_address", ethAddress)
                }.toString())
            } else {
                SkillResult.Error("Failed to set ETH address on contact $contactId")
            }
        } catch (e: IllegalArgumentException) {
            SkillResult.Error("Invalid ETH address: ${e.message}")
        } catch (e: Exception) {
            SkillResult.Error("Failed to set ETH address: ${e.message}")
        }
    }
}
