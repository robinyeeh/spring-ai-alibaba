package com.alibaba.cloud.ai.example.manus.dynamic.agent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import com.alibaba.cloud.ai.example.manus.agent.ReActAgent;
import com.alibaba.cloud.ai.example.manus.config.ManusProperties;
import com.alibaba.cloud.ai.example.manus.llm.LlmService;
import com.alibaba.cloud.ai.example.manus.recorder.PlanExecutionRecorder;
import com.alibaba.cloud.ai.example.manus.recorder.entity.AgentExecutionRecord;
import com.alibaba.cloud.ai.example.manus.recorder.entity.ThinkActRecord;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

public class DynamicAgent extends ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(DynamicAgent.class);

    private final String agentName;

    private final String agentDescription;

    private final String systemPrompt;

    private final String nextStepPrompt;

    private final Map<String, ToolCallback> toolCallbackMap;
    
    private final List<String> availableToolKeys;

    private ChatResponse response;

    private Prompt userPrompt;

    protected ThinkActRecord thinkActRecord;

    private final ToolCallingManager toolCallingManager;

    public DynamicAgent(LlmService llmService, PlanExecutionRecorder planExecutionRecorder,
            ManusProperties manusProperties, String name, String description, String systemPrompt,
            String nextStepPrompt, Map<String, ToolCallback> toolCallbackMap, List<String> availableToolKeys,
            ToolCallingManager toolCallingManager) {
        super(llmService, planExecutionRecorder, manusProperties);
        this.agentName = name;
        this.agentDescription = description;
        this.systemPrompt = systemPrompt;
        this.nextStepPrompt = nextStepPrompt;
        this.toolCallbackMap = toolCallbackMap;
        this.availableToolKeys = availableToolKeys;
        this.toolCallingManager = toolCallingManager;
    }

    @Override
    protected boolean think() {
        AgentExecutionRecord planExecutionRecord = planExecutionRecorder.getCurrentAgentExecutionRecord(getPlanId());
        thinkActRecord = new ThinkActRecord(planExecutionRecord.getId());
        planExecutionRecorder.recordThinkActExecution(getPlanId(), planExecutionRecord.getId(), thinkActRecord);

        try {
            List<Message> messages = new ArrayList<>();
            addThinkPrompt(messages);
            thinkActRecord.startThinking(messages.toString());

            ChatOptions chatOptions = ToolCallingChatOptions.builder().internalToolExecutionEnabled(false).build();
            Message nextStepMessage = getNextStepMessage();
            messages.add(nextStepMessage);

            log.debug("Messages prepared for the prompt: {}", messages);

            userPrompt = new Prompt(messages, chatOptions);

            response = llmService.getChatClient()
                    .prompt(userPrompt)
                    .advisors(memoryAdvisor -> memoryAdvisor.param(CHAT_MEMORY_CONVERSATION_ID_KEY, getConversationId())
                            .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100))
                    .tools(getToolCallList())
                    .call()
                    .chatResponse();

            List<ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
            String responseByLLm = response.getResult().getOutput().getText();

            thinkActRecord.finishThinking(responseByLLm);

            log.info(String.format("✨ %s's thoughts: %s", getName(), responseByLLm));
            log.info(String.format("🛠️ %s selected %d tools to use", getName(), toolCalls.size()));

            if (responseByLLm != null && !responseByLLm.isEmpty()) {
                log.info(String.format("💬 %s's response: %s", getName(), responseByLLm));
            }
            if (!toolCalls.isEmpty()) {
                log.info(String.format("🧰 Tools being prepared: %s",
                        toolCalls.stream().map(ToolCall::name).collect(Collectors.toList())));
                thinkActRecord.setActionNeeded(true);
                thinkActRecord.setToolName(toolCalls.get(0).name());
                thinkActRecord.setToolParameters(toolCalls.get(0).arguments());
            }

            thinkActRecord.setStatus("SUCCESS");

            return !toolCalls.isEmpty();
        } catch (Exception e) {
            log.error(String.format("🚨 Oops! The %s's thinking process hit a snag: %s", getName(), e.getMessage()));
            thinkActRecord.recordError(e.getMessage());
            return false;
        }
    }

    @Override
    protected String act() {
        try {
            List<String> results = new ArrayList<>();
            ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);

            thinkActRecord.startAction("Executing tool: " + toolCall.name(), toolCall.name(), toolCall.arguments());

            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(userPrompt, response);
            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult.conversationHistory()
                    .get(toolExecutionResult.conversationHistory().size() - 1);
            llmService.getMemory().add(getConversationId(), toolResponseMessage);
            String llmCallResponse = toolResponseMessage.getResponses().get(0).responseData();
            results.add(llmCallResponse);

            String finalResult = String.join("\n\n", results);
            log.info(String.format("🔧 Tool %s's executing result: %s", getName(), llmCallResponse));

            thinkActRecord.finishAction(finalResult, "SUCCESS");

            return finalResult;
        } catch (Exception e) {
            ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
            ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(toolCall.id(),
                    toolCall.name(), "Error: " + e.getMessage());
            ToolResponseMessage toolResponseMessage = new ToolResponseMessage(List.of(toolResponse), Map.of());
            llmService.getMemory().add(getConversationId(), toolResponseMessage);
            log.error(e.getMessage());

            thinkActRecord.recordError(e.getMessage());

            return "Error: " + e.getMessage();
        }
    }

    @Override
    public String getName() {
        return this.agentName;
    }

    @Override
    public String getDescription() {
        return this.agentDescription;
    }

    @Override
    protected Message getNextStepMessage() {
		PromptTemplate promptTemplate = new PromptTemplate(this.nextStepPrompt);
		Message userMessage = promptTemplate.createMessage(getData());
		return userMessage;
    }

    @Override
    protected Message addThinkPrompt(List<Message> messages) {
        super.addThinkPrompt(messages);
        SystemPromptTemplate promptTemplate = new SystemPromptTemplate(this.systemPrompt);
        Message systemMessage = promptTemplate.createMessage(getData());
        messages.add(systemMessage);
        return systemMessage;
    }

    @Override
    public List<ToolCallback> getToolCallList() {
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        for(String toolKey : availableToolKeys) {
            if (toolCallbackMap.containsKey(toolKey)) {
                ToolCallback toolCallback = toolCallbackMap.get(toolKey);
                if (toolCallback != null) {
                    toolCallbacks.add(toolCallback);
                }
            } else {
                log.warn("Tool callback for {} not found in the map.", toolKey);
            }
        }
        return toolCallbacks;
    }

    @Override
    protected Map<String, Object> getData() {
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> parentData = super.getData();
        if (parentData != null) {
            data.putAll(parentData);
        }
        return data;
    }

}
