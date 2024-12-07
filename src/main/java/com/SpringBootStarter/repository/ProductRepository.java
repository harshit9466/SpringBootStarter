// src/main/java/com/example/repository/ProductRepository.java

package com.SpringBootStarter.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.SpringBootStarter.model.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
//    @Query(nativeQuery = true, value = "SELECT ... your entire SQL query ...")
//    List<Product> getAppointmentsWithFilters(
//        @Param("role") String role,
//        @Param("fromDate") String fromDate,
//        @Param("toDate") String toDate,
//        @Param("clinicIds") List<Long> clinicIds,
//        @Param("teamIds") List<Long> teamIds,
//        @Param("patientIds") List<Long> patientIds,
//        @Param("serviceIds") List<Long> serviceIds,
//        @Param("statusIds") List<String> statusIds,
//        @Param("page") int page,
//        @Param("count") int count
//    );
}
