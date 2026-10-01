package com.loresentry.content.user;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserDataController {

    private final UserDataService userData;

    public UserDataController(UserDataService userData) {
        this.userData = userData;
    }

    /** 지울 것이 없어도 {@code 204}다. 탈퇴 재시도가 이 단계에서 막히면 안 된다. */
    @DeleteMapping("/users/me/data")
    public ResponseEntity<Void> purge(@CurrentUser UUID userId) {
        userData.purge(userId);
        return ResponseEntity.noContent().build();
    }
}
