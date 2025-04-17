package com.weather.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot application acting as an MCP Database Schema Server.
 * Uses Spring AI MCP Server Boot Starter for integration.
 * Provides tools to inspect PostgreSQL database schema.
 */
@SpringBootApplication
@EnableTransactionManagement
public class DatabaseMcpServer {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMcpServer.class);

    public static void main(String[] args) {
        SpringApplication.run(DatabaseMcpServer.class, args);
    }

    /**
     * Provides the ToolCallbacks to the Spring AI MCP AutoConfiguration.
     * This bean registers the methods in DatabaseSchemaService annotated with @Tool.
     * @param databaseSchemaService The service containing the database tools.
     * @return A ToolCallbackProvider configured with the database tools.
     */
    @Bean
    public ToolCallbackProvider databaseToolCallbackProvider(DatabaseSchemaService databaseSchemaService) {
        logger.info("Registering DatabaseSchemaService tools with MethodToolCallbackProvider");
        // Register tools from the DatabaseSchemaService instance
        return MethodToolCallbackProvider.builder().toolObjects(databaseSchemaService).build();
    }

} 