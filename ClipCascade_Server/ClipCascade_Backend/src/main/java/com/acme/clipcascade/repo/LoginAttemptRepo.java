package com.acme.clipcascade.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.acme.clipcascade.model.LoginAttempt;
import java.time.LocalDateTime;

public interface LoginAttemptRepo extends JpaRepository<LoginAttempt, Long> {

    @Query("SELECT COUNT(la) FROM LoginAttempt la " +
           "WHERE la.username = :username " +
           "AND la.attemptType = 'LOGIN_FAILURE' " +
           "AND la.timestamp > :since")
    int countFailuresForUsernameAfter(@Param("username") String username, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(la) FROM LoginAttempt la " +
           "WHERE la.ipAddress = :ipAddress " +
           "AND la.attemptType = 'LOGIN_FAILURE' " +
           "AND la.timestamp > :since")
    int countFailuresForIpAfter(@Param("ipAddress") String ipAddress, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(la) FROM LoginAttempt la " +
           "WHERE la.username = :username " +
           "AND la.ipAddress = :ipAddress " +
           "AND la.attemptType = 'LOGIN_FAILURE' " +
           "AND la.timestamp > :since")
    int countFailuresForUsernameAndIpAfter(@Param("username") String username,
                                           @Param("ipAddress") String ipAddress,
                                           @Param("since") LocalDateTime since);

    @Query("SELECT MIN(la.timestamp) FROM LoginAttempt la " +
           "WHERE la.username = :username " +
           "AND la.ipAddress = :ipAddress " +
           "AND la.attemptType = 'LOGIN_FAILURE' " +
           "AND la.timestamp > :since")
    LocalDateTime getOldestFailureTimestamp(@Param("username") String username,
                                            @Param("ipAddress") String ipAddress,
                                            @Param("since") LocalDateTime since);

    @Transactional
    @Modifying
    @Query("DELETE FROM LoginAttempt la WHERE la.timestamp < :olderThan")
    int deleteOlderThan(@Param("olderThan") LocalDateTime olderThan);

    @Transactional
    @Modifying
    @Query("DELETE FROM LoginAttempt la WHERE la.username = :username AND la.ipAddress = :ipAddress")
    int deleteByUsernameAndIpAddress(@Param("username") String username, @Param("ipAddress") String ipAddress);
}
