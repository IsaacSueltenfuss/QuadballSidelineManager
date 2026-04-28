package com.example.quadballsidelinemanager.utils

import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import java.io.File

/**
 * Helper class to manage Firebase Storage operations.
 * Adapted for the Quadball Sideline Manager project.
 */
class Storage {
    // Create a storage reference from our app, pointing to the "images" folder
    private val photoStorage: StorageReference =
        FirebaseStorage.getInstance().reference.child("headshots")

    /**
     * Cleans up local temporary files after an upload or failure.
     */
    private fun deleteLocalFile(localFile: File, uuid: String) {
        if (localFile.delete()) {
            Log.d(javaClass.simpleName, "Deleted local file $uuid")
        } else {
            Log.d(javaClass.simpleName, "Local file delete FAILED $uuid")
        }
    }

    /**
     * Uploads a file from local storage to Firebase Storage.
     */
    fun uploadImage(localFile: File, uuid: String, uploadSuccess: () -> Unit) {
        val file = Uri.fromFile(localFile)
        val uuidRef = photoStorage.child("$uuid.jpg")

        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpg")
            .build()

        val uploadTask = uuidRef.putFile(file, metadata)

        uploadTask
            .addOnFailureListener { e ->
                Log.e(javaClass.simpleName, "Upload FAILED for $uuid", e)
                deleteLocalFile(localFile, uuid)
            }
            .addOnSuccessListener {
                Log.d(javaClass.simpleName, "Upload SUCCESS for $uuid")
                uploadSuccess()
                deleteLocalFile(localFile, uuid)
            }
    }

    /**
     * Deletes an image from the cloud based on its UUID.
     */
    fun deleteImage(pictureUUID: String) {
        photoStorage.child(pictureUUID).delete()
            .addOnSuccessListener {
                Log.d(javaClass.simpleName, "Deleted $pictureUUID from cloud")
            }
            .addOnFailureListener { e ->
                Log.e(javaClass.simpleName, "Delete FAILED for $pictureUUID", e)
            }
    }

    /**
     * Retrieves the downloadable URL for a given image UUID.
     * Use this with Glide to load images into your UI.
     */
    fun getDownloadUrl(pictureUUID: String): Task<Uri> {
        return photoStorage.child(pictureUUID).downloadUrl
    }

    /**
     * Fetches a list of all image UUIDs currently in storage.
     */
    fun listAllImages(listSuccess: (List<String>) -> Unit) {
        photoStorage.listAll()
            .addOnSuccessListener { listResult ->
                Log.d(javaClass.simpleName, "listAllImages count: ${listResult.items.size}")
                val pictureUUIDs = listResult.items.map { it.name }
                listSuccess(pictureUUIDs)
            }
            .addOnFailureListener { e ->
                Log.e(javaClass.simpleName, "listAllImages FAILED", e)
            }
    }

    fun getPlayerHeadshot(playerId: String): Task<Uri> {
        val storageRef = FirebaseStorage.getInstance().reference
        val jpgRef = storageRef.child("headshots/$playerId.jpg")
        val jpegRef = storageRef.child("headshots/$playerId.jpeg")

        val tcs = TaskCompletionSource<Uri>()

        // Attempt to get the .jpg first
        jpgRef.downloadUrl.addOnSuccessListener { uri ->
            tcs.setResult(uri)
        }.addOnFailureListener {
            // If .jpg fails, immediately attempt the .jpeg
            jpegRef.downloadUrl.addOnSuccessListener { uri ->
                tcs.setResult(uri)
            }.addOnFailureListener { exception ->
                // If both fail, return the final error
                tcs.setException(exception)
            }
        }

        return tcs.task
    }

    /**
     * Helper to get a direct StorageReference for advanced FirebaseUI operations.
     */
    fun uuid2StorageReference(pictureUUID: String): StorageReference {
        return photoStorage.child(pictureUUID)
    }
}