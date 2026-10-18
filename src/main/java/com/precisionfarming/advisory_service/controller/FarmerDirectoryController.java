package com.precisionfarming.advisory_service.controller;

import com.precisionfarming.advisory_service.model.User;
import com.precisionfarming.advisory_service.repository.CommunityDao;
import com.precisionfarming.advisory_service.repository.DistrictDao;
import com.precisionfarming.advisory_service.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashSet;

@Controller
public class FarmerDirectoryController {

    private final CommunityDao community;
    private final DistrictDao districts;
    private final UserRepository users;

    public FarmerDirectoryController(CommunityDao community, DistrictDao districts, UserRepository users) {
        this.community = community;
        this.districts = districts;
        this.users = users;
    }

    @GetMapping("/farmers")
    public String findFarmers(Authentication auth, Model model,
                              @RequestParam(name = "district", required = false) String district,
                              @RequestParam(name = "name", required = false) String name) {
        Long me = users.findByUsername(auth.getName()).map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Logged-in user not found"));
        String selectedDistrict = district == null ? "" : district.trim();
        if (!selectedDistrict.isEmpty() && !districts.nameExists(selectedDistrict)) {
            selectedDistrict = "";
        }
        String search = name == null ? "" : name.trim();
        if (search.length() > 80) {
            search = search.substring(0, 80);
        }
        model.addAttribute("members", community.searchMembers(selectedDistrict, search));
        model.addAttribute("following", new HashSet<>(community.followingIds(me)));
        model.addAttribute("me", me);
        model.addAttribute("districts", districts.findAll());
        model.addAttribute("selectedDistrict", selectedDistrict);
        model.addAttribute("nameSearch", search);
        return "pages/farmers";
    }
}