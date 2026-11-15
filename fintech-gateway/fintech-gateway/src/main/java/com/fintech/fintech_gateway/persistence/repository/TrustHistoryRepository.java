package com.fintech.fintech_gateway.persistence.repository;

import com.fintech.fintech_gateway.persistence.entity.TrustHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TrustHistoryRepository extends JpaRepository<TrustHistoryEntity, Long> {

    long countByTimestampBetween(Instant from, Instant to);

    @Query("""
            select t.newTier as value, count(t) as total
            from TrustHistoryEntity t
            where t.timestamp between :from and :to
            group by t.newTier
            """)
    List<CountByValue> countByNewTierBetween(@Param("from") Instant from,
                                             @Param("to") Instant to);
}
