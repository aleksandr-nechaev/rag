package com.nechaev.repository;

import com.nechaev.model.IngestionState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionStateRepository extends JpaRepository<IngestionState, String> {
}
