package com.fixhub.platform;

import org.springframework.boot.SpringApplication;

public class TestFixhubCoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(FixhubCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
