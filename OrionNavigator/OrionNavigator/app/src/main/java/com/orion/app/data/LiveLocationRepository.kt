package com.orion.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository for Live Location Firebase operations
 */
class LiveLocationRepository {
    
    // Use explicit database URL for Asia Southeast region
    private val database = FirebaseDatabase.getInstance("https://orion-live-location-default-rtdb.asia-southeast1.firebasedatabase.app")
    private val auth = FirebaseAuth.getInstance()
    
    private val locationsRef = database.getReference("locations")
    private val usersRef = database.getReference("users")
    private val linksRef = database.getReference("links")
    
    companion object {
        private const val TIMEOUT_MS = 10_000L // 10 seconds timeout
    }
    
    /**
     * Get current user ID (anonymous or authenticated)
     */
    suspend fun getCurrentUserId(): String {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }
        // Sign in anonymously with timeout
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            auth.signInAnonymously().await()
        } ?: throw Exception("Login timeout - pastikan Anonymous Auth sudah di-enable di Firebase Console")
        
        return result.user?.uid ?: throw Exception("Failed to sign in")
    }
    
    /**
     * Get current user's role
     */
    suspend fun getUserRole(userId: String): UserRole? {
        val snapshot = usersRef.child(userId).child("role").get().await()
        val roleString = snapshot.getValue(String::class.java) ?: return null
        return try {
            UserRole.valueOf(roleString.uppercase())
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Set user role
     */
    suspend fun setUserRole(userId: String, role: UserRole) {
        usersRef.child(userId).child("role").setValue(role.name.lowercase()).await()
    }
    
    /**
     * Update location for a user (child role)
     */
    suspend fun updateLocation(
        userId: String, lat: Double, lng: Double,
        accuracy: Float = 0f, speed: Float = 0f, bearing: Float = 0f
    ) {
        val locationData = LocationData(
            lat = lat,
            lng = lng,
            updatedAt = System.currentTimeMillis(),
            isActive = true,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing
        )
        locationsRef.child(userId).setValue(locationData).await()
    }
    
    /**
     * Set live location active status
     */
    suspend fun setLocationActive(userId: String, isActive: Boolean) {
        locationsRef.child(userId).child("isActive").setValue(isActive).await()
        if (!isActive) {
            locationsRef.child(userId).child("updatedAt").setValue(System.currentTimeMillis()).await()
        }
    }
    
    /**
     * Check if live location is active
     */
    suspend fun isLocationActive(userId: String): Boolean {
        val snapshot = locationsRef.child(userId).child("isActive").get().await()
        return snapshot.getValue(Boolean::class.java) ?: false
    }
    
    /**
     * Observe location changes for a child (used by parent)
     */
    fun observeLocation(childId: String): Flow<LocationData?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val locationData = snapshot.getValue(LocationData::class.java)
                trySend(locationData)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        locationsRef.child(childId).addValueEventListener(listener)
        
        awaitClose {
            locationsRef.child(childId).removeEventListener(listener)
        }
    }
    
    /**
     * Observe only the isActive status for a child (used by parent).
     * This is separate from observeLocation to catch status changes immediately,
     * even before a GPS fix updates the full LocationData.
     */
    fun observeLocationActive(childId: String): Flow<Boolean> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isActive = snapshot.getValue(Boolean::class.java) ?: false
                trySend(isActive)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        locationsRef.child(childId).child("isActive").addValueEventListener(listener)
        
        awaitClose {
            locationsRef.child(childId).child("isActive").removeEventListener(listener)
        }
    }
    
    /**
     * Link a parent to a child using linking code
     */
    suspend fun linkParent(childId: String, parentId: String): Boolean {
        val link = UserLink(
            childId = childId,
            parentId = parentId,
            linkedAt = System.currentTimeMillis(),
            isActive = true
        )
        
        // Store link
        linksRef.child(childId).setValue(link).await()
        
        // Update user references
        usersRef.child(childId).child("linkedParent").setValue(parentId).await()
        usersRef.child(parentId).child("linkedChild").setValue(childId).await()
        
        return true
    }
    
    /**
     * Get linked child ID for a parent
     */
    suspend fun getLinkedChildId(parentId: String): String? {
        val snapshot = usersRef.child(parentId).child("linkedChild").get().await()
        return snapshot.getValue(String::class.java)
    }
    
    /**
     * Get linked parent ID for a child
     */
    suspend fun getLinkedParentId(childId: String): String? {
        val snapshot = usersRef.child(childId).child("linkedParent").get().await()
        return snapshot.getValue(String::class.java)
    }
    
    /**
     * Observe linked parent status in real-time (for child to know when parent connects)
     */
    fun observeLinkedParent(childId: String): Flow<String?> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val parentId = snapshot.getValue(String::class.java)
                trySend(parentId)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        usersRef.child(childId).child("linkedParent").addValueEventListener(listener)
        
        awaitClose {
            usersRef.child(childId).child("linkedParent").removeEventListener(listener)
        }
    }
    
    /**
     * Unlink parent from child
     */
    suspend fun unlinkParent(childId: String) {
        // Get parent ID first
        val parentId = getLinkedParentId(childId)
        
        // Remove link from child
        usersRef.child(childId).child("linkedParent").removeValue().await()
        usersRef.child(childId).child("linkCode").removeValue().await()
        usersRef.child(childId).child("linkCodeExpiry").removeValue().await()
        
        // Remove link from parent
        parentId?.let {
            usersRef.child(it).child("linkedChild").removeValue().await()
        }
        
        // Remove link record
        linksRef.child(childId).removeValue().await()
    }
    
    /**
     * Generate a simple 6-digit linking code for the child
     */
    suspend fun generateLinkCode(childId: String): String {
        val code = (100000..999999).random().toString()
        
        val success = withTimeoutOrNull(TIMEOUT_MS) {
            usersRef.child(childId).child("linkCode").setValue(code).await()
            usersRef.child(childId).child("linkCodeExpiry").setValue(System.currentTimeMillis() + 5 * 60 * 1000).await()
            true
        } ?: throw Exception("Database timeout - pastikan Realtime Database sudah dibuat di Firebase Console")
        
        return code
    }
    
    /**
     * Find child by link code (for parent to connect)
     * Uses full scan instead of orderByChild to avoid index requirement
     */
    suspend fun findChildByLinkCode(code: String): String? {
        val snapshot = withTimeoutOrNull(TIMEOUT_MS) {
            usersRef.get().await()
        } ?: throw Exception("Database timeout")
        
        if (!snapshot.exists()) return null
        
        for (userSnapshot in snapshot.children) {
            val linkCode = userSnapshot.child("linkCode").getValue(String::class.java)
            if (linkCode == code) {
                val expiry = userSnapshot.child("linkCodeExpiry").getValue(Long::class.java) ?: 0L
                if (System.currentTimeMillis() < expiry) {
                    return userSnapshot.key
                }
            }
        }
        return null
    }
    
    // ==================== SAFE ZONES (GEOFENCE) ====================
    
    private fun safeZonesRef(childId: String) = usersRef.child(childId).child("safeZones")
    
    /**
     * Add a new safe zone for a child
     */
    suspend fun addSafeZone(childId: String, zone: SafeZone): String {
        val newRef = safeZonesRef(childId).push()
        val zoneWithId = zone.copy(id = newRef.key ?: "")
        newRef.setValue(zoneWithId.toMap()).await()
        return zoneWithId.id
    }
    
    /**
     * Get all safe zones for a child
     */
    suspend fun getSafeZones(childId: String): List<SafeZone> {
        val snapshot = safeZonesRef(childId).get().await()
        if (!snapshot.exists()) return emptyList()
        
        return snapshot.children.mapNotNull { zoneSnapshot ->
            try {
                @Suppress("UNCHECKED_CAST")
                val map = zoneSnapshot.value as? Map<String, Any?> ?: return@mapNotNull null
                SafeZone.fromMap(map)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Observe safe zones in real-time
     */
    fun observeSafeZones(childId: String): Flow<List<SafeZone>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val zones = snapshot.children.mapNotNull { zoneSnapshot ->
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val map = zoneSnapshot.value as? Map<String, Any?> ?: return@mapNotNull null
                        SafeZone.fromMap(map)
                    } catch (e: Exception) {
                        null
                    }
                }
                trySend(zones)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        safeZonesRef(childId).addValueEventListener(listener)
        
        awaitClose {
            safeZonesRef(childId).removeEventListener(listener)
        }
    }
    
    /**
     * Update a safe zone
     */
    suspend fun updateSafeZone(childId: String, zone: SafeZone) {
        safeZonesRef(childId).child(zone.id).setValue(zone.toMap()).await()
    }
    
    /**
     * Delete a safe zone
     */
    suspend fun deleteSafeZone(childId: String, zoneId: String) {
        safeZonesRef(childId).child(zoneId).removeValue().await()
    }
    
    /**
     * Toggle safe zone active status
     */
    suspend fun toggleSafeZoneActive(childId: String, zoneId: String, isActive: Boolean) {
        safeZonesRef(childId).child(zoneId).child("isActive").setValue(isActive).await()
    }
    
    // ==================== GEOFENCE EVENTS ====================
    
    private fun geofenceEventsRef(childId: String) = usersRef.child(childId).child("geofenceEvents")
    
    /**
     * Save a geofence event (enter/exit)
     */
    suspend fun saveGeofenceEvent(childId: String, zoneId: String, zoneName: String, eventType: String) {
        val event = mapOf(
            "zoneId" to zoneId,
            "zoneName" to zoneName,
            "eventType" to eventType, // "enter" or "exit"
            "timestamp" to System.currentTimeMillis()
        )
        geofenceEventsRef(childId).push().setValue(event).await()
    }
    
    /**
     * Observe geofence events in real-time (for pendamping to see alerts)
     */
    fun observeGeofenceEvents(childId: String): Flow<List<Map<String, Any?>>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val events = snapshot.children.mapNotNull { eventSnapshot ->
                    @Suppress("UNCHECKED_CAST")
                    eventSnapshot.value as? Map<String, Any?>
                }.sortedByDescending { it["timestamp"] as? Long ?: 0L }
                trySend(events)
            }
            
            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        
        geofenceEventsRef(childId).limitToLast(10).addValueEventListener(listener)
        
        awaitClose {
            geofenceEventsRef(childId).removeEventListener(listener)
        }
    }
}
