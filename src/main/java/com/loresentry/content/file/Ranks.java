package com.loresentry.content.file;

/**
 * 형제 사이의 정렬 키를 만든다. 두 이웃 <b>사이</b>의 문자열을 계산하므로, 항목을 옮길 때
 * 다른 행의 순서 값을 다시 쓰지 않는다 — 한 행만 UPDATE 하면 된다.
 *
 * <p>알파벳이 ASCII 오름차순이고 DB 컬럼이 {@code COLLATE "C"}라서, 자바의 문자열 비교와
 * PostgreSQL의 정렬이 같은 순서를 낸다. 로케일 의존 정렬을 쓰면 이 둘이 어긋난다.
 */
public final class Ranks {

    static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private Ranks() {
    }

    /** 목록이 비어 있을 때의 첫 값. 양쪽으로 자리가 남도록 가운데에서 시작한다. */
    public static String first() {
        return between(null, null);
    }

    /**
     * {@code before}와 {@code after} 사이의 값. 양 끝은 {@code null}로 표현한다.
     *
     * @throws IllegalArgumentException {@code before >= after}일 때. 호출자가 이웃을 잘못 읽은 것이다
     */
    public static String between(String before, String after) {
        if (before != null && after != null && before.compareTo(after) >= 0) {
            throw new IllegalArgumentException("before must sort before after: " + before + ", " + after);
        }

        StringBuilder out = new StringBuilder();
        String upper = after;

        for (int i = 0; ; i++) {
            int low = before != null && i < before.length() ? indexOf(before.charAt(i)) : 0;
            // 위쪽 끝이 없으면 알파벳 끝보다 하나 큰 값으로 본다.
            int high = upper != null && i < upper.length() ? indexOf(upper.charAt(i)) : ALPHABET.length();

            if (low == high) {
                out.append(ALPHABET.charAt(low));
                continue;
            }

            int mid = (low + high) / 2;
            if (mid > low) {
                return out.append(ALPHABET.charAt(mid)).toString();
            }

            // 두 자리가 붙어 있어 사이에 넣을 문자가 없다. 아래쪽 문자를 유지하고 한 자리 더 내려간다.
            // 그 순간부터 위쪽 경계는 의미가 없어진다 — 접두사가 이미 더 작기 때문이다.
            out.append(ALPHABET.charAt(low));
            upper = null;
        }
    }

    private static int indexOf(char value) {
        int index = ALPHABET.indexOf(value);
        if (index < 0) {
            throw new IllegalArgumentException("not a rank character: " + value);
        }
        return index;
    }
}
