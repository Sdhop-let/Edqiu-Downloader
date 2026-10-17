package com.ed.edqiu.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionMessageDao {

    @Query("SELECT * FROM subscription_messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SubscriptionMessageEntity>>

    @Query("SELECT COUNT(*) FROM subscription_messages WHERE read = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM subscription_messages WHERE read = 0")
    suspend fun countUnread(): Int

    @Insert
    suspend fun insert(entity: SubscriptionMessageEntity): Long

    @Update
    suspend fun update(entity: SubscriptionMessageEntity)

    @Query("UPDATE subscription_messages SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("DELETE FROM subscription_messages")
    suspend fun clearAll()

    /** 裁剪最旧记录，仅保留 [keep] 条。 */
    @Query(
        """
        DELETE FROM subscription_messages WHERE id NOT IN (
            SELECT id FROM subscription_messages ORDER BY createdAt DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun trim(keep: Int)
}
