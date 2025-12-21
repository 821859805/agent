package com.chatbi.agent;

import com.chatbi.agent.chatbi_server_java.service.LlmModelFactoryService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class testQwen3 {
    @Autowired
    LlmModelFactoryService service;

    @Test
    public void test1(){
        ChatLanguageModel model = service.getOrCreateModel("qwen3-max");
        System.out.println(model.generate("你好"));
    }

}
