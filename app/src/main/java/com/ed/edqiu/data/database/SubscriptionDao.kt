package com.ed.edqiu.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE screenName = :screenName LIMIT 1")
    suspend fun getByScreenName(screenName: String): SubscriptionEntity?

    /** 轮询候选：enabled 且最久未检查的 ≤limit 个（lastCheckedAt 为 null 的最优先）。 */
    @Query(
        """
        SELECT * FROM subscriptions
        WHERE enabled = 1
        ORDER BY (lastCheckedAt IS NULL) DESC, lastCheckedAt ASC
        LIMIT :limit
        """
    )
    suspend fun getEnabled(limit: Int): List<SubscriptionEntity>

    @Query("SELECT COUNT(*) FROM subscriptions WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SubscriptionEntity)

    @Update
    suspend fun update(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE screenName = :screenName")
    suspend fun deleteByScreenName(screenName: String)
}
