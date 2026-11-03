package com.example.demo.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    @RequestMapping("/hello")
    public String hello() {
        return "Hello, Spring Boot!";
    }

    @RequestMapping("/hello-paicli")
    public String helloPaicli() {
        return "Hello, PaiCLI!";
    }
}
