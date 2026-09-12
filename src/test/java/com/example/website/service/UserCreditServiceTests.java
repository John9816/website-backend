package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.entity.User;
import com.example.website.repository.UserCheckInRepository;
import com.example.website.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserCreditServiceTests {
    @Mock UserRepository users;
    @Mock UserCheckInRepository checkIns;
    @Mock SysConfigService configs;

    @Test
    void checkInAwardsConfiguredRewardAndPreventsSecondCheckIn() {
        User user = new User();
        user.setId(7L);
        user.setCredits(2);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(checkIns.existsByUserIdAndCheckInDate(eq(7L), any())).thenReturn(false, true);
        when(checkIns.findTopByUserIdOrderByCheckInDateDesc(7L)).thenReturn(Optional.empty());
        when(configs.getValue(UserCreditService.CFG_CHECKIN_REWARD)).thenReturn(Optional.of("3"));
        when(configs.getValue(UserCreditService.CFG_IMAGE_COST)).thenReturn(Optional.of("1"));

        UserCreditService service = new UserCreditService(users, checkIns, configs);
        assertEquals(5, service.checkIn(7L).getCredits());
        verify(checkIns).save(any());
        assertThrows(BusinessException.class, () -> service.checkIn(7L));
    }
}
