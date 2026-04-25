package com.beingbmc.aigateway.repository;

import com.beingbmc.aigateway.domain.LlmRequestInfo;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LlmRequestInfoRepository extends ReactiveMongoRepository<LlmRequestInfo, String> {
}
