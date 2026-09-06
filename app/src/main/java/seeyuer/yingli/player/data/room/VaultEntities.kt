package seeyuer.yingli.player.data.room

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "vault_items", primaryKeys = ["id"], indices = [Index("createdAtEpochMillis")])
data class VaultItemEntity(
    val id: String,
    val encryptedContentToken: String,
    val encryptedMetadataToken: String,
    val encryptedBytes: Long,
    val keyVersion: Int,
    val createdAtEpochMillis: Long,
)
