package de.fubo.appserver;

import org.springframework.boot.SpringApplication;

public class TestAppServerApplication {

	public static void main(String[] args) {
		SpringApplication.from(AppServerApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
