package org.ethereumphone.andyclaw.skills.builtin

import android.content.ContentResolver
import android.content.ContentValues
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

class ContactsSkill(private val context: Context) : AndyClawSkill {
    override val id = "contacts"
    override val name = "Contacts"

    override val baseManifest = SkillManifest(
        description = "Search and view contacts on the device.",
        tools = listOf(
            ToolDefinition(
                name = "search_contacts",
                description = "Search contacts by name or phone number. Returns matching contacts.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "query" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Search query (name or phone number)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("query"))),
                )),
            ),
            ToolDefinition(
                name = "get_contact_details",
                description = "Get full details of a contact by contact ID.",
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
            ),
        ),
        permissions = listOf("android.permission.READ_CONTACTS"),
    )

    override val privilegedManifest = SkillManifest(
        description = "Create and edit contacts (privileged OS only).",
        tools = listOf(
            ToolDefinition(
                name = "create_contact",
                description = "Create a new contact with name and phone number.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Contact display name"))),
                        "phone" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Phone number"))),
                        "email" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Email address"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("name"))),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "edit_contact",
                description = "Edit an existing contact's details.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contact_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("The contact ID to edit"))),
                        "name" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("New display name"))),
                        "phone" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("New phone number"))),
                    )),
                    "required" to JsonArray(listOf(JsonPrimitive("contact_id"))),
                )),
                requiresApproval = true,
            ),
        ),
        permissions = listOf("android.permission.WRITE_CONTACTS"),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "search_contacts" -> searchContacts(params)
            "get_contact_details" -> getContactDetails(params)
            "create_contact" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("create_contact requires privileged OS")
                else createContact(params)
            }
            "edit_contact" -> {
                if (tier != Tier.PRIVILEGED) SkillResult.Error("edit_contact requires privileged OS")
                else editContact(params)
            }
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private fun searchContacts(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: query")
        return try {
            val contacts = mutableListOf<JsonObject>()
            val cr = context.contentResolver
            val cursor = cr.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$query%"),
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            )
            cursor?.use {
                while (it.moveToNext() && contacts.size < 20) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    contacts.add(buildJsonObject {
                        put("id", id)
                        put("name", name ?: "")
                    })
                }
            }
            SkillResult.Success(JsonArray(contacts).toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to search contacts: ${e.message}")
        }
    }

    private fun getContactDetails(params: JsonObject): SkillResult {
        val contactId = params["contact_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contact_id")
        return try {
            val cr = context.contentResolver
            val result = buildJsonObject {
                put("id", contactId)
                // Get name
                val nameCursor = cr.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(contactId),
                    null,
                )
                nameCursor?.use {
                    if (it.moveToFirst()) put("name", it.getString(0) ?: "")
                }
                // Get phones
                val phones = mutableListOf<String>()
                val phoneCursor = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null,
                )
                phoneCursor?.use {
                    while (it.moveToNext()) phones.add(it.getString(0) ?: "")
                }
                put("phones", JsonArray(phones.map { JsonPrimitive(it) }))
                // Get emails
                val emails = mutableListOf<String>()
                val emailCursor = cr.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null,
                )
                emailCursor?.use {
                    while (it.moveToNext()) emails.add(it.getString(0) ?: "")
                }
                put("emails", JsonArray(emails.map { JsonPrimitive(it) }))
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get contact details: ${e.message}")
        }
    }

    private fun createContact(params: JsonObject): SkillResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: name")
        return try {
            val ops = ArrayList<android.content.ContentProviderOperation>()
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            params["phone"]?.jsonPrimitive?.contentOrNull?.let { phone ->
                ops.add(
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
            params["email"]?.jsonPrimitive?.contentOrNull?.let { email ->
                ops.add(
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .build()
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            SkillResult.Success(buildJsonObject { put("success", true); put("name", name) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to create contact: ${e.message}")
        }
    }

    private fun editContact(params: JsonObject): SkillResult {
        val contactId = params["contact_id"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contact_id")
        return try {
            params["name"]?.jsonPrimitive?.contentOrNull?.let { name ->
                val values = ContentValues().apply {
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                }
                context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    values,
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                )
            }
            SkillResult.Success(buildJsonObject { put("success", true); put("contact_id", contactId) }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to edit contact: ${e.message}")
        }
    }
}
