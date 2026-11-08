package com.example.website.module.music.api.dto;

import com.example.website.shared.dto.PageView;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPlaylistDetailView {

    private UserPlaylistView playlist;
    private PageView<UserPlaylistItemView> items;
}
