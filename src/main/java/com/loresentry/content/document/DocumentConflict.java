package com.loresentry.content.document;

/**
 * 다른 탭이나 기기가 먼저 저장했다. 응답에 <b>현재 문서와 공통 조상</b>을 함께 실어, 클라이언트가
 * 3-way 병합을 시도할 수 있게 한다(TABLE_AND_LOGIC §7.4).
 *
 * <p>{@code base}는 클라이언트가 들고 있던 revision 의 스냅샷이다. 자동 버전이 남기지 않은
 * revision 이면 비어 있고, 그때 클라이언트는 2-way 로 물어볼 수밖에 없다.
 */
public class DocumentConflict extends RuntimeException {

    private final DocumentResponses.Content current;

    private final DocumentSnapshot base;

    public DocumentConflict(DocumentResponses.Content current, DocumentSnapshot base) {
        super("document was saved elsewhere first");
        this.current = current;
        this.base = base;
    }

    public DocumentResponses.Content current() {
        return current;
    }

    public DocumentSnapshot base() {
        return base;
    }
}
