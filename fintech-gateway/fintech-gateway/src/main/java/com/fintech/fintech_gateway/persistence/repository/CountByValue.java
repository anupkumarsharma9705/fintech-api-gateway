package com.fintech.fintech_gateway.persistence.repository;

/**
 * Projection for grouped evidence counts. Spring Data maps the JPQL aliases
 * {@code value} and {@code total} to these accessors.
 */
public interface CountByValue {

    String getValue();

    long getTotal();
}
