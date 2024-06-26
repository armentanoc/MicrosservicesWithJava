package br.ucsal.ispserverapp.controller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException.NotFound;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.ucsal.ispserverapp.BadRequestException;
import br.ucsal.ispserverapp.viewmodels.*;
import jakarta.ws.rs.ClientErrorException;

@RestController
public class IspServerController {

    private final RestTemplate restTemplate;
    private final String registryUrl = "http://dns-server-app:8081/getRegisteredApplications";

    public IspServerController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/perfil")
    public ResponseEntity<?> handleProfileRequest(@RequestBody ProfileRequestDTO requestBody)
            throws JsonMappingException, JsonProcessingException {
        var responseJson = restTemplate.getForObject(registryUrl, String.class);

        if (responseJson == null || responseJson.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Registry response is empty");
        }

        var instanceUrl = findInstanceUrl(responseJson, "PERFIL-APP");

        if (instanceUrl == null || instanceUrl.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("PERFIL-APP instance URL not found");
        }

        instanceUrl += "perfil";
        System.out.println("[ISP-SERVER][REDIRECTING] to target URL: " + instanceUrl);

        ProfileResponseDTO responseEntity = restTemplate.postForObject(instanceUrl, requestBody,
                ProfileResponseDTO.class);
        return ResponseEntity.ok(responseEntity);
    }

    @PostMapping("/validacao")
    public ResponseEntity<?> handleValidationRequest(@RequestBody ValidacaoRequestDTO requestBody)
            throws JsonMappingException, JsonProcessingException {
        var responseJson = restTemplate.getForObject(registryUrl, String.class);

        if (responseJson == null || responseJson.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Registry response is empty");
        }

        var instanceUrl = findInstanceUrl(responseJson, "VALIDACAO-APP");

        if (instanceUrl == null || instanceUrl.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("VALIDACAO-APP instance URL not found");
        }

        instanceUrl += "validacao";
        System.out.println("[ISP-SERVER][REDIRECTING] to target URL: " + instanceUrl);

        ValidacaoResponseDTO responseEntity = restTemplate.postForObject(instanceUrl, requestBody,
                ValidacaoResponseDTO.class);
        return ResponseEntity.ok(responseEntity);
    }

    @PostMapping(value = "/perfil/salvarArquivo/{fileName}")
    public ResponseEntity<?> uploadFile(
            @RequestPart("file") MultipartFile file,
            @PathVariable("fileName") String fileName) throws Exception {

        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException(
                    "É necessário informar na rota um nome para o arquivo: '/perfil/salvarArquivo/{fileName}'.");
        }

        if (!fileName.matches("^perfis_v\\d+\\.txt$")) {
            throw new BadRequestException("O nome do arquivo deve ser perfis_vNumber.txt (ex.: perfis_v1.txt)");
        }

        if (file == null || file.isEmpty()) {
            throw new BadRequestException("É necessário enviar um arquivo não vazio do tipo .txt");
        }

        if (!"text/plain".equals(file.getContentType())) {
            throw new BadRequestException(
                    "Apenas arquivos do tipo .txt são aceitos (fileType: " + file.getContentType() + ")");
        }

        var responseJson = restTemplate.getForObject(registryUrl, String.class);

        if (responseJson == null || responseJson.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Registry response is empty");
        }

        var instanceUrl = findInstanceUrl(responseJson, "PERFIL-APP");

        instanceUrl += "salvarArquivo";
        System.out.println("[ISP-SERVER][REDIRECTING] to target URL: " + instanceUrl);

        RestTemplate restTemplate = new RestTemplate();

        var convertedFile = convertMultipartFileToFile(file);
        var requestBody = new SaveFileRequestDTO(convertedFile, fileName);
        ResponseEntity<SaveFileResponseDTO> response = restTemplate.postForEntity(instanceUrl, requestBody,
                SaveFileResponseDTO.class);

        if (response.getBody().isSuccess()) {
            URI location = new URI("C:/Volumes/SD-trabalho-2/ProfileVersions/" + fileName);
            return ResponseEntity.created(location)
                    .body(response.getBody());
        }

        return ResponseEntity.unprocessableEntity()
                .body(response.getBody());
    }

    @GetMapping(value = "/perfil/obterArquivo/{fileName}")
    public ResponseEntity<Resource> getFile(@PathVariable("fileName") String fileName) throws Exception {

        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException(
                    "É necessário informar na rota um nome para o arquivo: '/perfil/obterArquivo/{fileName}'.");
        }

        if (!fileName.matches("^perfis_v\\d+\\.txt$")) {
            throw new BadRequestException("O nome do arquivo deve ser perfis_vNumber.txt (ex.: perfis_v1.txt)");
        }

        var responseJson = restTemplate.getForObject(registryUrl, String.class);

        if (responseJson == null || responseJson.isEmpty()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }

        var instanceUrl = findInstanceUrl(responseJson, "PERFIL-APP");

        instanceUrl += "obterArquivo";
        System.out.println("[ISP-SERVER][REDIRECTING] to target URL: " + instanceUrl);

        RestTemplate restTemplate = new RestTemplate();
        var requestBody = new GetFileRequestDTO(fileName);

        ResponseEntity<GetFileResponseDTO> response = restTemplate.postForEntity(instanceUrl, requestBody,
                GetFileResponseDTO.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                && response.getBody().getFile() != null) {

            File file = response.getBody().getFile();
            Path filePath = file.toPath();
            Resource resource = new FileSystemResource(filePath);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
        } else {
            throw new IllegalArgumentException("Arquivo não encontrado ou não disponível para download.");
        }
    }

    private String findInstanceUrl(String responseJson, String appName)
            throws JsonMappingException, JsonProcessingException {

        var mapper = new ObjectMapper();
        var rootNode = mapper.readTree(responseJson);
        var applicationsNode = rootNode.path("applications").path("application");

        for (JsonNode applicationNode : applicationsNode) {
            var name = applicationNode.path("name").asText();

            if (name.equals(appName)) {
                var instanceNode = applicationNode.path("instance").get(0);
                var instanceUrl = instanceNode.path("homePageUrl").asText();

                if (!instanceUrl.startsWith("http://") && !instanceUrl.startsWith("https://")) {
                    instanceUrl = "http://" + instanceUrl;
                }

                return instanceUrl;
            }
        }

        throw new RuntimeException("[ERROR] Application not found: " + appName);
    }

    public static File convertMultipartFileToFile(MultipartFile multipartFile) throws IOException {
        File file = new File("/app/InputFiles/" + multipartFile.getOriginalFilename());
        try (OutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(multipartFile.getBytes());
        }
        return file;
    }
}
