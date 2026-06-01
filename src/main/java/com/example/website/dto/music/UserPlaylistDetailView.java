package com.example.website.dto.music;

import com.example.website.dto.PageView;
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
