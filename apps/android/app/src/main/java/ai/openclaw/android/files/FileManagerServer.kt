package ai.openclaw.android.files

import android.content.Context
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream

/**
 * File Manager HTTP Server - Management fișiere via API
 * 
 * Endpoints:
 * - GET  /files/list?path={path}     - Listează fișiere director
 * - GET  /files/download?path={path} - Descarcă fișier
 * - POST /files/upload?path={path}   - Upload fișier
 * - POST /files/mkdir?path={path}    - Creează director
 * - DELETE /files/delete?path={path} - Șterge fișier/director
 * - GET  /files/search?q={query}     - Caută fișiere
 * - GET  /files/stats                - Statistici storage
 * 
 * Port: 8894
 */
class FileManagerServer(
    private val context: Context,
    private val port: Int = 8894
) : NanoHTTPD(port) {
    
    private val json = Json { prettyPrint = true }
    private val baseDir: File by lazy {
        Environment.getExternalStorageDirectory()
    }
    
    @Serializable
    data class FileInfo(
        val name: String,
        val path: String,
        val type: String, // file, directory
        val size: Long,
        val lastModified: Long,
        val permissions: String,
        val isReadable: Boolean,
        val isWritable: Boolean
    )
    
    @Serializable
    data class DirectoryListing(
        val path: String,
        val parent: String?,
        val files: List<FileInfo>,
        val totalCount: Int,
        val totalSize: Long
    )
    
    @Serializable
    data class StorageStats(
        val totalSpace: Long,
        val freeSpace: Long,
        val usableSpace: Long,
        val usedSpace: Long,
        val usedPercent: Float
    )
    
    @Serializable
    data class FileOperationResponse(
        val success: Boolean,
        val message: String,
        val path: String? = null
    )
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parameters
        
        return try {
            when {
                uri == "/files/list" && method == Method.GET ->
                    handleList(params["path"]?.firstOrNull() ?: "/")
                uri == "/files/download" && method == Method.GET ->
                    handleDownload(params["path"]?.firstOrNull())
                uri == "/files/upload" && method == Method.POST ->
                    handleUpload(session, params["path"]?.firstOrNull() ?: "/")
                uri == "/files/mkdir" && method == Method.POST ->
                    handleMkdir(params["path"]?.firstOrNull())
                uri == "/files/delete" && method == Method.DELETE ->
                    handleDelete(params["path"]?.firstOrNull())
                uri == "/files/search" && method == Method.GET ->
                    handleSearch(params["q"]?.firstOrNull())
                uri == "/files/stats" && method == Method.GET ->
                    handleStats()
                method == Method.OPTIONS ->
                    handleOptions()
                else ->
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Not Found\"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("FileManager", "Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleList(path: String): Response {
        return try {
            val dir = resolvePath(path)
            
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Directory not found\"}")
            }
            
            val files = dir.listFiles()?.map { file ->
                FileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    type = if (file.isDirectory) "directory" else "file",
                    size = file.length(),
                    lastModified = file.lastModified(),
                    permissions = getPermissions(file),
                    isReadable = file.canRead(),
                    isWritable = file.canWrite()
                )
            }?.sortedWith(compareBy<FileInfo> { it.type }.thenBy { it.name }) ?: emptyList()
            
            val listing = DirectoryListing(
                path = path,
                parent = dir.parent,
                files = files,
                totalCount = files.size,
                totalSize = files.filter { it.type == "file" }.sumOf { it.size }
            )
            
            newJsonResponse(listing)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleDownload(path: String?): Response {
        if (path == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"Path required\"}")
        }
        
        return try {
            val file = resolvePath(path)
            
            if (!file.exists() || file.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"File not found\"}")
            }
            
            val mimeType = getMimeType(file.name)
            newChunkedResponse(Response.Status.OK, mimeType, FileInputStream(file))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleUpload(session: IHTTPSession, path: String): Response {
        return try {
            val dir = resolvePath(path)
            
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Directory not found\"}")
            }
            
            val files = HashMap<String, String>()
            session.parseBody(files)
            
            // Procesează uploaded files
            val uploadedFiles = mutableListOf<String>()
            
            files.forEach { (name, tempFilePath) ->
                val tempFile = File(tempFilePath)
                if (tempFile.exists()) {
                    val targetFile = File(dir, name)
                    tempFile.copyTo(targetFile, overwrite = true)
                    uploadedFiles.add(name)
                }
            }
            
            newJsonResponse(FileOperationResponse(
                success = true,
                message = "Uploaded ${uploadedFiles.size} files",
                path = path
            ))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleMkdir(path: String?): Response {
        if (path == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"Path required\"}")
        }
        
        return try {
            val dir = resolvePath(path)
            
            if (dir.exists()) {
                return newJsonResponse(FileOperationResponse(success = false, message = "Already exists", path = path))
            }
            
            val success = dir.mkdirs()
            newJsonResponse(FileOperationResponse(
                success = success,
                message = if (success) "Directory created" else "Failed to create directory",
                path = path
            ))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleDelete(path: String?): Response {
        if (path == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"Path required\"}")
        }
        
        return try {
            val file = resolvePath(path)
            
            if (!file.exists()) {
                return newJsonResponse(FileOperationResponse(success = false, message = "Not found", path = path))
            }
            
            val success = file.deleteRecursively()
            newJsonResponse(FileOperationResponse(
                success = success,
                message = if (success) "Deleted successfully" else "Failed to delete",
                path = path
            ))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleSearch(query: String?): Response {
        if (query.isNullOrBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "{\"error\":\"Query required\"}")
        }
        
        return try {
            val results = mutableListOf<FileInfo>()
            searchFiles(baseDir, query, results, 100)
            
            newJsonResponse(mapOf("results" to results, "count" to results.size, "query" to query))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun searchFiles(dir: File, query: String, results: MutableList<FileInfo>, maxResults: Int) {
        if (results.size >= maxResults) return
        
        dir.listFiles()?.forEach { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                results.add(FileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    type = if (file.isDirectory) "directory" else "file",
                    size = file.length(),
                    lastModified = file.lastModified(),
                    permissions = getPermissions(file),
                    isReadable = file.canRead(),
                    isWritable = file.canWrite()
                ))
            }
            
            if (file.isDirectory && results.size < maxResults) {
                searchFiles(file, query, results, maxResults)
            }
        }
    }
    
    private fun handleStats(): Response {
        return try {
            val stats = StorageStats(
                totalSpace = baseDir.totalSpace,
                freeSpace = baseDir.freeSpace,
                usableSpace = baseDir.usableSpace,
                usedSpace = baseDir.totalSpace - baseDir.freeSpace,
                usedPercent = ((baseDir.totalSpace - baseDir.freeSpace).toFloat() / baseDir.totalSpace.toFloat()) * 100
            )
            
            newJsonResponse(stats)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) {
            File(baseDir, path.substring(1))
        } else {
            File(baseDir, path)
        }
    }
    
    private fun getPermissions(file: File): String {
        return buildString {
            append(if (file.canRead()) "r" else "-")
            append(if (file.canWrite()) "w" else "-")
            append(if (file.canExecute()) "x" else "-")
        }
    }
    
    private fun getMimeType(filename: String): String {
        return when (filename.substringAfterLast(".", "").lowercase()) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
    
    private fun newJsonResponse(data: Any): Response {
        val jsonString = when (data) {
            is String -> data
            else -> json.encodeToString(data)
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}