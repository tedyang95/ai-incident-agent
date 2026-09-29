package com.example.agent.repository;

import com.example.agent.model.Incident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Page<Incident> findBySeverity(String severity, Pageable pageable);

    Page<Incident> findByService(String service, Pageable pageable);

    Page<Incident> findBySeverityAndService(String severity, String service, Pageable pageable);

    long countBySeverity(String severity);

    long countByStatus(Incident.AnalysisStatus status);
}
