package vn.edu.finalism.logistics;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import vn.edu.finalism.logistics.TestWorld.TestUser;

final class TestApi {

	private final MockMvc mvc;

	TestApi(MockMvc mvc) {
		this.mvc = mvc;
	}

	String login(TestUser user) throws Exception {
		String body = "{\"identifier\":\"" + user.email() + "\",\"password\":\"" + TestWorld.PASSWORD + "\"}";
		ResultActions result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		return JsonPath.read(bodyOf(result), "$.accessToken");
	}

	ResultActions post(String token, String path, String body, String... headers) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		for (int i = 0; i + 1 < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return mvc.perform(request);
	}

	ResultActions put(String token, String path, String body, String... headers) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.put(path)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		for (int i = 0; i + 1 < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return mvc.perform(request);
	}

	ResultActions patch(String token, String path, String body, String... headers) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.patch(path)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body);
		for (int i = 0; i + 1 < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return mvc.perform(request);
	}

	ResultActions get(String token, String path) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token));
	}

	ResultActions getWithHeaders(String token, String path, String... headers) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token);
		for (int i = 0; i + 1 < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		return mvc.perform(request);
	}

	ResultActions getAnonymous(String path) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.get(path));
	}

	ResultActions putBytes(String token, String path, byte[] content) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.put(path).header("Authorization", "Bearer " + token)
				.contentType(MediaType.IMAGE_JPEG).content(content));
	}

	static String bodyOf(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	static String etagOf(ResultActions result) {
		return result.andReturn().getResponse().getHeader("ETag");
	}
}
