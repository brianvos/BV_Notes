package com.bv.notes.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.bv.notes.data.model.NoteElement

object ElementSerializer {
    private val gson = Gson()
    private val listType = object : TypeToken<List<NoteElement>>() {}.type

    fun serialize(elements: List<NoteElement>): String = gson.toJson(elements)
    fun deserialize(json: String?): MutableList<NoteElement> =
        if (json.isNullOrBlank() || json == "[]") mutableListOf()
        else try { gson.fromJson(json, listType) } catch (e: Exception) { mutableListOf() }
}
