package com.loresentry.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling 은 스케줄링 기능을 켠다. 이것이 있어야 Spring 이 스케줄러를 만들고 @Scheduled 가 붙은 메서드를
// 찾아 주기적으로 부른다. 없으면 @Scheduled 는 오류 없이 아무 일도 하지 않는다.
@SpringBootApplication
@EnableScheduling
public class ContentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentApplication.class, args);
    }
}
