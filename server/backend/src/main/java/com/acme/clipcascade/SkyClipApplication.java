/**
 * SkyClip - Self-hosted, end-to-end encrypted clipboard sync
 * 
 * Repository: https://github.com/C0R3DMP/SkyClip
 * License: GPL-3.0 (See LICENSE file for details)
 * 
 * 
 * 
 * 
 * 
 * 
 */

package com.acme.clipcascade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SkyClipApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkyClipApplication.class, args);
	}
}
