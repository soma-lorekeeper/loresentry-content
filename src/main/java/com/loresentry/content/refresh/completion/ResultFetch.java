package com.loresentry.content.refresh.completion;

/**
 * 완료 이벤트를 받고 DB 트랜잭션을 열기 전에 결과 파일을 가져온 결과({@link GraphRefreshCompletionService#fetchResult}).
 *
 * <p>S3 를 읽는 동안 DB connection 을 잡지 않으려고 가져오기와 반영({@link GraphRefreshCompletionService#complete})을 나눈다.
 * 가져오기는 무엇을 반영할지 정할 재료만 만들고, 상태는 바꾸지 않는다.
 */
public sealed interface ResultFetch {

    /** 반영할 것이 없다. run 이 없거나(프로젝트 삭제) 이미 끝났다(중복·늦은 이벤트). S3 는 읽지 않았다. */
    record Skip() implements ResultFetch {
    }

    /** 실패 이벤트이고 결과 파일 위치가 없다. 이벤트의 실패 원인만으로 끝낸다. */
    record NoResult() implements ResultFetch {
    }

    /**
     * 결과 파일을 쓸 수 없다(없음·만료·너무 큼·내용 불일치 등). run 은 이 코드로 실패한다.
     *
     * @param code    {@code refresh_runs.error_code} 에 남길 값
     * @param message 진단용 설명
     */
    record Unusable(String code, String message) implements ResultFetch {
    }

    /**
     * 결과 파일을 읽었고 이 요청의 결과가 맞다.
     *
     * @param json   파일 원본(UTF-8 문자열). {@code refresh_results} 에 그대로 남긴다
     * @param sha256 원본 바이트의 SHA-256(소문자 16진수). graph-rag 가 S3 메타데이터에 적은 값과 같음을 확인했다
     */
    record Read(RefreshResult result, String json, String sha256, String bucket, String key) implements ResultFetch {
    }
}
