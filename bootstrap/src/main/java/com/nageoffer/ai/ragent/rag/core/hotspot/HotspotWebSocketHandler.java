/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.hotspot;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class HotspotWebSocketHandler extends TextWebSocketHandler {

    private static final String USER_ID_ATTR = "userId";

    private final ObjectMapper objectMapper;

    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractQueryParam(session.getUri(), "token");
        Object loginId = StrUtil.isBlank(token) ? null : StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid token"));
            return;
        }
        String userId = String.valueOf(loginId);
        session.getAttributes().put(USER_ID_ATTR, userId);
        sessionsByUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(session);
        send(session, Map.of(
                "type", "connected",
                "userId", userId,
                "timestamp", System.currentTimeMillis()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = StrUtil.trimToEmpty(message.getPayload());
        if ("ping".equalsIgnoreCase(payload)) {
            send(session, Map.of("type", "pong", "timestamp", System.currentTimeMillis()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        removeSession(session);
    }

    public boolean sendToUser(String userId, Object payload) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return false;
        }
        boolean delivered = false;
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    send(session, payload);
                    delivered = true;
                } catch (Exception ex) {
                    log.debug("Failed to deliver hotspot websocket message: userId={}", userId, ex);
                }
            }
        }
        return delivered;
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    private void removeSession(WebSocketSession session) {
        Object userId = session.getAttributes().get(USER_ID_ATTR);
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByUser.get(String.valueOf(userId));
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUser.remove(String.valueOf(userId));
        }
    }

    private String extractQueryParam(URI uri, String key) {
        if (uri == null || StrUtil.isBlank(uri.getQuery())) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            String[] entry = part.split("=", 2);
            if (entry.length == 2 && key.equals(entry[0])) {
                return entry[1];
            }
        }
        return null;
    }
}
