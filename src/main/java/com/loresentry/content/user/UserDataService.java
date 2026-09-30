package com.loresentry.content.user;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.media.MediaCleanup;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 때 이 사용자의 콘텐츠를 모두 지운다(이용약관 제7조). 유예 기간은 없다.
 *
 * <p>여러 번 불려도 결과가 같다. gateway 는 이 호출이 끝난 뒤 계정을 지우므로, 계정 삭제가 실패하면
 * 사용자가 탈퇴를 다시 시도하고 이 호출도 다시 온다.
 */
@Service
public class UserDataService {

    private final UserDataRepository repository;

    private final MediaCleanup mediaCleanup;

    public UserDataService(UserDataRepository repository, MediaCleanup mediaCleanup) {
        this.repository = repository;
        this.mediaCleanup = mediaCleanup;
    }

    @Transactional
    public void purge(UUID ownerUserId) {
        List<String> imageKeys = repository.imageKeysOf(ownerUserId);
        repository.deleteWorkspaceStates(ownerUserId);
        repository.deleteProjects(ownerUserId);
        mediaCleanup.deleteAfterCommit(imageKeys);
    }
}
