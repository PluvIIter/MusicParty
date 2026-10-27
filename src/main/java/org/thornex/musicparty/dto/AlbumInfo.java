package org.thornex.musicparty.dto;

import java.util.List;

public record AlbumInfo(String id, String name, List<String> artists, String coverUrl,
                        int trackCount, List<Music> songs) {}
