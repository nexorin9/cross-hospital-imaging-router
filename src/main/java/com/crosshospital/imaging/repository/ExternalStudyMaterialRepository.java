package com.crosshospital.imaging.repository;

import com.crosshospital.imaging.domain.ExternalStudyMaterial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 外院检查资料（V9）。 */
public interface ExternalStudyMaterialRepository extends JpaRepository<ExternalStudyMaterial, String> {

    Optional<ExternalStudyMaterial> findByStudyUid(String studyUid);
}
