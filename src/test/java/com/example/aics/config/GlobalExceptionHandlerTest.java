package com.example.aics.config;

import com.example.aics.agent.AgentAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void duplicateAccountReturnsConflictWithActionableMessage() {
        ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                .accountAlreadyExists(new AgentAccountService.AccountAlreadyExistsException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("ok", false)
                .containsEntry("error", "登录账号已存在，请更换一个账号");
    }
}
