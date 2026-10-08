package fr.cafat.gpp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class GppApplication {

  public static void main(String[] args) {
    SpringApplication.run(GppApplication.class, args);
  }
}
