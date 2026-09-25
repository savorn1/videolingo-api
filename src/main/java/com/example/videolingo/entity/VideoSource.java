package com.example.videolingo.entity;

// Where a video's media lives. UPLOAD = a file in our bucket; the platform
// values are played through that platform's embed player; URL = a video
// file hosted elsewhere, played directly.
public enum VideoSource {
    UPLOAD,
    YOUTUBE,
    VIMEO,
    FACEBOOK,
    URL
}
