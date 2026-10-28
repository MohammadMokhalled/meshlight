package com.example.meshlightcontroller.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

object CdbValidator {
    private fun hex(value: String, length: Int) =
        value.length == length && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    private fun identity(value: String): String {
        val compact = value.replace("-", "")
        require(hex(compact, 32)) { "Invalid mesh identity" }
        return UUID.fromString(compact.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5")).toString().uppercase()
    }
    private fun key(value: JsonObject) {
        require(hex(requireNotNull(value["key"]?.asString), 32)) { "Invalid mesh key" }
        value["oldKey"]?.takeUnless { it.isJsonNull }?.let {
            require(hex(it.asString, 32)) { "Invalid old mesh key" }
        }
    }

    fun validate(json: String): String {
        require(json.toByteArray().size <= 12 * 1024 * 1024) { "Mesh database is too large" }
        val root = JsonParser.parseString(json).asJsonObject
        val id = identity(requireNotNull(root["meshUUID"]?.asString) { "Missing mesh network identity" })
        val nodes = requireNotNull(root.getAsJsonArray("nodes")) { "Missing mesh nodes" }
        val keys = requireNotNull(root.getAsJsonArray("netKeys")) { "Missing network keys" }
        require(keys.size() in 1..4096 && nodes.size() <= 1000)
        val indexes = mutableSetOf<Int>()
        keys.forEach {
            val value = it.asJsonObject
            require(value["index"].asInt in 0..4095 && indexes.add(value["index"].asInt)) { "Invalid network key index" }
            key(value)
            value["phase"]?.let { phase -> require(phase.asInt in 0..2) { "Invalid key refresh phase" } }
        }
        val appIndexes = mutableSetOf<Int>()
        root.getAsJsonArray("appKeys")?.let { appKeys ->
            require(appKeys.size() <= 4096)
            appKeys.forEach {
                val value = it.asJsonObject
                require(value["index"].asInt in 0..4095 && appIndexes.add(value["index"].asInt)) { "Invalid application key index" }
                require(value["boundNetKey"].asInt in indexes) { "Missing bound network key" }
                key(value)
            }
        }
        val provisioners = root.getAsJsonArray("provisioners")?.map {
            identity(it.asJsonObject["UUID"].asString)
        }.orEmpty().toSet()
        val ranges = mutableListOf<IntRange>()
        val uuids = mutableSetOf<String>()
        nodes.forEach { value ->
            val node = value.asJsonObject
            val uuid = identity(node["UUID"].asString)
            require(uuids.add(uuid)) { "Duplicate node identity" }
            val deviceKey = node["deviceKey"]?.takeUnless { it.isJsonNull }?.asString
            require((deviceKey == null && uuid in provisioners) || (deviceKey != null && hex(deviceKey, 32))) {
                "This file is missing device credentials needed to configure a light"
            }
            val address = node["unicastAddress"].asString.toInt(16)
            val count = node.getAsJsonArray("elements")?.size()?.coerceAtLeast(1) ?: 1
            require(count <= 255 && address in 1..0x7FFF && address + count - 1 <= 0x7FFF) { "Invalid node address" }
            val range = address until address + count
            require(ranges.none { it.first <= range.last && range.first <= it.last }) { "Overlapping node addresses" }
            ranges += range
        }
        root.getAsJsonArray("groups")?.forEach {
            val address = it.asJsonObject["address"].asString
            if (address.length == 4) require(address.toInt(16) in 0xC000..0xFEFF) { "Unsupported group address" }
            else identity(address)
        }
        return id
    }
}
