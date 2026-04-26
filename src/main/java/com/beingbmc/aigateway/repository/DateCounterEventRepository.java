package com.beingbmc.aigateway.repository;

import com.beingbmc.aigateway.domain.DateCounterEvent;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DateCounterEventRepository extends ReactiveMongoRepository<DateCounterEvent, String> {
}
