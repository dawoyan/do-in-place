package com.davoyans.doinplace.data.location

interface PlaceSearchProvider {
    suspend fun search(query: String): Result<List<PlaceSearchResult>>
}
