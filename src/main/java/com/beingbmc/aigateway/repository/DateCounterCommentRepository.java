package com.beingbmc.aigateway.repository;

import com.beingbmc.aigateway.domain.DateCounterComment;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DateCounterCommentRepository extends ReactiveMongoRepository<DateCounterComment, String> {
}
