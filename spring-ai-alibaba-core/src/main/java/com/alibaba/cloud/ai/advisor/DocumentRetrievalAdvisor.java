/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.advisor;

import org.springframework.ai.chat.client.AdvisedRequest;
import org.springframework.ai.chat.client.RequestResponseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentRetriever;
import org.springframework.ai.model.Content;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Title Document retrieval advisor.<br>
 * Description Document retrieval advisor.<br>
 *
 * @author yuanci.ytb
 * @since 2024/8/16 11:29
 */

public class DocumentRetrievalAdvisor implements RequestResponseAdvisor {

	private static final String DEFAULT_USER_TEXT_ADVISE = """
			请记住以下材料，他们可能对回答问题有帮助。
			---------------------
			{documents}
			---------------------
			""";

	public static String RETRIEVED_DOCUMENTS = "documents";

	private final DocumentRetriever retriever;

	private final String userTextAdvise;

	public DocumentRetrievalAdvisor(DocumentRetriever retriever) {
		this.retriever = retriever;
		this.userTextAdvise = DEFAULT_USER_TEXT_ADVISE;
	}

	public DocumentRetrievalAdvisor(DocumentRetriever retriever, String userTextAdvise) {
		this.retriever = retriever;
		this.userTextAdvise = userTextAdvise;
	}

	@Override
	public AdvisedRequest adviseRequest(AdvisedRequest request, Map<String, Object> context) {
		List<Document> documents = retriever.retrieve(request.userText());

		context.put(RETRIEVED_DOCUMENTS, documents);

		String documentContext = documents.stream()
			.map(Content::getContent)
			.collect(Collectors.joining(System.lineSeparator()));

		Map<String, Object> advisedUserParams = new HashMap<>(request.userParams());
		advisedUserParams.put(RETRIEVED_DOCUMENTS, documentContext);

		return AdvisedRequest.from(request)
			.withSystemText(this.userTextAdvise)
			.withSystemParams(advisedUserParams)
			.withUserText(request.userText())
			.build();
	}

	@Override
	public ChatResponse adviseResponse(ChatResponse response, Map<String, Object> context) {
		ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(response);
		return chatResponseBuilder.withMetadata(RETRIEVED_DOCUMENTS, context.get(RETRIEVED_DOCUMENTS)).build();
	}

	@Override
	public Flux<ChatResponse> adviseResponse(Flux<ChatResponse> fluxResponse, Map<String, Object> context) {
		return fluxResponse.map(cr -> {
			ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(cr);
			return chatResponseBuilder.withMetadata(RETRIEVED_DOCUMENTS, context.get(RETRIEVED_DOCUMENTS)).build();
		});
	}

}
