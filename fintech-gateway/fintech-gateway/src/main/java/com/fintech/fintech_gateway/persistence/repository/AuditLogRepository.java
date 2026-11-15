package com.fintech.fintech_gateway.persistence.repository;

import com.fintech.fintech_gateway.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    long countByTimestampBetween(Instant from, Instant to);

    long countByAttackTypeInAndTimestampBetween(Collection<String> attackTypes,
                                                Instant from, Instant to);

    long countByClientIdIsNullAndTimestampBetween(Instant from, Instant to);

    @Query("""
            select count(distinct a.tenantId)
            from AuditLogEntity a
            where a.timestamp between :from and :to
            """)
    long countDistinctTenantsBetween(@Param("from") Instant from,
                                     @Param("to") Instant to);

    @Query("""
            select count(distinct a.clientId)
            from AuditLogEntity a
            where a.timestamp between :from and :to
            """)
    long countDistinctClientsBetween(@Param("from") Instant from,
                                     @Param("to") Instant to);

    Optional<AuditLogEntity> findFirstByTimestampBetweenOrderByTimestampAsc(
            Instant from, Instant to);

    Optional<AuditLogEntity> findFirstByTimestampBetweenOrderByTimestampDesc(
            Instant from, Instant to);

    List<AuditLogEntity> findByTimestampBetweenOrderByTimestampAsc(
            Instant from, Instant to, Pageable pageable);

    @Query("""
            select a.decision as value, count(a) as total
            from AuditLogEntity a
            where a.timestamp between :from and :to
            group by a.decision
            """)
    List<CountByValue> countByDecisionBetween(@Param("from") Instant from,
                                              @Param("to") Instant to);

    @Query("""
            select a.attackType as value, count(a) as total
            from AuditLogEntity a
            where a.timestamp between :from and :to
              and a.attackType is not null
            group by a.attackType
            """)
    List<CountByValue> countByAttackTypeBetween(@Param("from") Instant from,
                                                @Param("to") Instant to);
}
