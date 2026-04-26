package com.beingbmc.aigateway.repository;

import com.beingbmc.aigateway.domain.Conversation;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversationRepository extends ReactiveMongoRepository<Conversation, String> {
}
