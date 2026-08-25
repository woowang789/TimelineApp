package com.timeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationProperties 클래스(JwtProperties)는 컴포넌트 스캔 대상이 아니라 별도 스캔이 필요하다.
@SpringBootApplication
@ConfigurationPropertiesScan
public class TimelineApplication {

	public static void main(String[] args) {
		SpringApplication.run(TimelineApplication.class, args);
	}

}
