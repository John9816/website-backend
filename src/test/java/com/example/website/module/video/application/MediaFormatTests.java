package com.example.website.module.video.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaFormatTests {

    @Test
    void mapsProgressiveContainersToMp4() {
        assertEquals("mp4", MediaFormat.infer("https://cdn.example.com/a/b.mp4"));
        assertEquals("mp4", MediaFormat.infer("https://cdn.example.com/a/b.MOV"));
        assertEquals("mp4", MediaFormat.infer("https://cdn.example.com/a/b.m4v"));
        assertEquals("mp4", MediaFormat.infer("https://cdn.example.com/a/b.f4v"));
    }

    @Test
    void keepsStreamingContainersDistinct() {
        assertEquals("hls", MediaFormat.infer("https://cdn.example.com/live/index.m3u8"));
        assertEquals("dash", MediaFormat.infer("https://cdn.example.com/live/manifest.mpd"));
    }

    @Test
    void reportsOtherKnownContainersVerbatim() {
        assertEquals("mkv", MediaFormat.infer("https://cdn.example.com/a/b.mkv"));
        assertEquals("webm", MediaFormat.infer("https://cdn.example.com/a/b.webm"));
        assertEquals("flv", MediaFormat.infer("https://cdn.example.com/a/b.flv"));
        assertEquals("avi", MediaFormat.infer("https://cdn.example.com/a/b.avi"));
        assertEquals("ts", MediaFormat.infer("https://cdn.example.com/a/b.ts"));
        assertEquals("ts", MediaFormat.infer("https://cdn.example.com/a/b.m2ts"));
        assertEquals("3gp", MediaFormat.infer("https://cdn.example.com/a/b.3gpp"));
    }

    @Test
    void ignoresQueryAndFragmentWhenReadingExtension() {
        assertEquals("mp4", MediaFormat.infer("https://cdn.example.com/a/b.mp4?token=abc&t=1"));
        assertEquals("mkv", MediaFormat.infer("https://cdn.example.com/a/b.mkv#t=120"));
        assertEquals("hls", MediaFormat.infer("https://cdn.example.com/a/index.m3u8?sign=x"));
    }

    @Test
    void neverFallsBackToHlsForUnknownUrls() {
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.infer("https://cdn.example.com/duanju/video302"));
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.infer("https://cdn.example.com/a/b"));
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.infer("https://cdn.example.com/a/b.php"));
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.infer(""));
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.infer(null));
    }

    @Test
    void recognisesMediaUrlsThatUsedToBeDropped() {
        assertTrue(MediaFormat.isMediaUrl("https://cdn.example.com/a/b.mkv"));
        assertTrue(MediaFormat.isMediaUrl("https://cdn.example.com/a/b.ts"));
        assertTrue(MediaFormat.isMediaUrl("https://cdn.example.com/a/b.flv"));
        assertTrue(MediaFormat.isMediaUrl("https://cdn.example.com/a/b.mp4?x=1"));
        assertTrue(MediaFormat.isMediaUrl("https://cdn.example.com/a/index.m3u8"));
    }

    @Test
    void rejectsNonMediaUrls() {
        assertFalse(MediaFormat.isMediaUrl("https://cdn.example.com/a/b.jpg"));
        assertFalse(MediaFormat.isMediaUrl("https://cdn.example.com/detail/12345"));
        assertFalse(MediaFormat.isMediaUrl("https://cdn.example.com/duanju/video302"));
        assertFalse(MediaFormat.isMediaUrl(null));
    }
}
