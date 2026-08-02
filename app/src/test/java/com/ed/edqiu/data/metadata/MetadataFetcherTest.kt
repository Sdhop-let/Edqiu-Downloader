package com.ed.edqiu.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataFetcherTest {

    @Test
    fun parsesAuthorAvatarHandleAndCaptionFromFxTwitterResponse() {
        val body =
            """
            {
              "code": 200,
              "tweet": {
                "id": "1234567890123456789",
                "text": "A quiet video caption",
                "author": {
                  "name": "Video Author",
                  "screen_name": "video_author",
                  "avatar_url": "https://cdn.example/avatar.jpg"
                },
                "media": {
                  "videos": [
                    { "thumbnail_url": "https://cdn.example/video-thumb.jpg" }
                  ]
                }
              }
            }
            """.trimIndent()

        val meta = MetadataFetcher().parseTwitterResponse(body)

        assertEquals("1234567890123456789", meta?.tweetId)
        assertEquals("@video_author", meta?.authorId)
        assertEquals("Video Author", meta?.authorName)
        assertEquals("A quiet video caption", meta?.caption)
        assertEquals("https://cdn.example/avatar.jpg", meta?.avatarUrl)
        assertEquals("https://cdn.example/video-thumb.jpg", meta?.thumbnailUrl)
    }

    @Test
    fun parsesPhotoUrlAsThumbnailFromFxTwitterResponse() {
        val body =
            """
            {
              "code": 200,
              "tweet": {
                "id": "9876543210987654321",
                "text": "A still image post",
                "author": {
                  "name": "Photo Author",
                  "screen_name": "photo_author",
                  "avatar_url": "https://cdn.example/photo-avatar.jpg"
                },
                "media": {
                  "photos": [
                    { "url": "https://pbs.twimg.com/media/photo_one?format=jpg&name=large" }
                  ]
                }
              }
            }
            """.trimIndent()

        val meta = MetadataFetcher().parseTwitterResponse(body)

        assertEquals("9876543210987654321", meta?.tweetId)
        assertEquals("@photo_author", meta?.authorId)
        assertEquals("Photo Author", meta?.authorName)
        assertEquals("A still image post", meta?.caption)
        assertEquals("https://cdn.example/photo-avatar.jpg", meta?.avatarUrl)
        assertEquals("https://pbs.twimg.com/media/photo_one?format=jpg&name=large", meta?.thumbnailUrl)
    }
}
