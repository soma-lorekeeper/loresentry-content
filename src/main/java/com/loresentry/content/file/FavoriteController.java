package com.loresentry.content.file;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 즐겨찾기는 원본을 가리키는 바로가기다(요구사항 §4.2). 그래서 본문 없는 PUT·DELETE 두 개이고,
 * 둘 다 갱신된 전체 목록을 돌려준다 — 화면이 목록을 다시 요청하지 않아도 된다.
 */
@RestController
public class FavoriteController {

    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @GetMapping("/projects/{projectId}/favorites")
    public FavoriteService.Favorites list(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return favorites.list(userId, projectId);
    }

    @PutMapping("/projects/{projectId}/favorites/{fileId}")
    public FavoriteService.Favorites add(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @PathVariable UUID fileId) {
        return favorites.add(userId, projectId, fileId);
    }

    @DeleteMapping("/projects/{projectId}/favorites/{fileId}")
    public FavoriteService.Favorites remove(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @PathVariable UUID fileId) {
        return favorites.remove(userId, projectId, fileId);
    }
}
