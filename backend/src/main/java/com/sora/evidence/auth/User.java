package com.sora.evidence.auth;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 60)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;
    @Column(nullable = false, length = 60)
    private String name;
    @Column(nullable = false, length = 20)
    private String role;
    @Column(name = "employee_no", length = 30)
    private String employeeNo;
    @Column(name = "hire_date")
    private LocalDate hireDate;
    @Column(length = 60)
    private String department;

    protected User() {
    }

    public User(String username, String passwordHash, String name, String role, String employeeNo, LocalDate hireDate, String department) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.employeeNo = employeeNo;
        this.hireDate = hireDate;
        this.department = department;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public String getDepartment() {
        return department;
    }

    public boolean isStaff() {
        return "HR".equals(role) || "ADMIN".equals(role);
    }
}
