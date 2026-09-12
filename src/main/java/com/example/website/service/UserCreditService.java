package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.dto.UserCreditView;
import com.example.website.entity.User;
import com.example.website.entity.UserCheckIn;
import com.example.website.repository.UserCheckInRepository;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class UserCreditService {
    public static final String CFG_IMAGE_COST = "image.generate.credit_cost";
    public static final String CFG_CHECKIN_REWARD = "user.checkin.reward_credits";
    public static final String CFG_INITIAL_CREDITS = "user.register.initial_credits";

    private final UserRepository userRepository;
    private final UserCheckInRepository checkInRepository;
    private final SysConfigService configService;

    @Transactional(readOnly = true)
    public UserCreditView get(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        UserCheckIn last = checkInRepository.findTopByUserIdOrderByCheckInDateDesc(userId).orElse(null);
        LocalDate today = LocalDate.now();
        LocalDate lastDate = last == null ? null : last.getCheckInDate();
        return new UserCreditView(user.getCredits(), config(CFG_IMAGE_COST, 1),
                config(CFG_CHECKIN_REWARD, 5), today.equals(lastDate), lastDate);
    }

    @Transactional
    public UserCreditView checkIn(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        LocalDate today = LocalDate.now();
        if (checkInRepository.existsByUserIdAndCheckInDate(userId, today)) {
            throw new BusinessException(400, "今日已签到，请明天再来");
        }
        int reward = config(CFG_CHECKIN_REWARD, 5);
        UserCheckIn checkIn = new UserCheckIn();
        checkIn.setUserId(userId);
        checkIn.setCheckInDate(today);
        checkIn.setRewardCredits(reward);
        try {
            checkInRepository.save(checkIn);
            checkInRepository.flush();
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(400, "今日已签到，请明天再来");
        }
        if (reward > 0) {
            user.setCredits(user.getCredits() + reward);
            userRepository.save(user);
        }
        return get(userId);
    }

    public int initialCredits() {
        return config(CFG_INITIAL_CREDITS, 10);
    }

    private int config(String key, int fallback) {
        try {
            return configService.getValue(key)
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .filter(value -> value >= 0)
                    .orElse(fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
